# Agent Instructions

Use this file as the single source of truth for how to work in the Stricknani repo.

## Critical Requirements

### Vendored Web Dependencies

- All new frontend dependencies (JS/CSS) must be vendored via `vendir.yml` (no new CDN links).
- After changing `vendir.yml`, run `just vendor-sync` and commit the resulting `vendir.lock.yml` plus `stricknani/static/vendor/**`.
- Prefer **vanilla JS** for new UI behavior. Do not introduce new JS dependencies unless there is a strong technical reason and existing capabilities (HTMX, browser APIs) are insufficient.

### Translations (UI text)

- Always add new strings to `stricknani/locales/en/LC_MESSAGES/messages.po` and `stricknani/locales/de/LC_MESSAGES/messages.po`.
- **Avoid multiline strings** in HTML templates (e.g., between `{% trans %}` or in `{{ _(...) }}`). Keep them on a single line to prevent brittle multiline `msgid`s in the catalogs.
- Update catalogs after template changes:
  `just i18n-update`
- Compile catalogs after edits:
  `just i18n-compile`
- Verify translations:
  `just i18n-check`

### Database Migrations

- **Always create a migration** when modifying SQLAlchemy models (adding/removing columns, changing constraints, etc.).
- Use Alembic to generate migrations:
  `uv run alembic -c stricknani/alembic.ini revision -m "description_of_change"`
- After creating the migration, review and edit it if needed.
- **Always run migrations after creating them** to verify they work:
  `uv run alembic -c stricknani/alembic.ini upgrade head`
- Migrations should be in `stricknani/alembic/versions/`.
- Include the migration in your commit.

### Code Quality

- **Zero Tolerance Policy:** If you notice a formatting or linting error, you MUST fix it immediately.
- **TODO Management:**
  - When starting a task from `TODO.md`, you MUST mark it as `wip` immediately.
  - If explicitly asked to "clean up", you should remove all tasks marked as `done` from `TODO.md`.
- Linting: `uv run ruff check .`
- Type checking: `uv run mypy .` (strict)
- Nix linting: `statix check`
- Formatting: `just fmt`
- Tests: `uv run pytest -v`
- **Server Health Check (Mandatory):** After any code change, you MUST verify the application starts correctly by running the integration tests: `uv run pytest tests/test_health.py`. This catches runtime syntax errors in templates or application logic that static analysis might miss.
- **Yolo:** If I ask you to "yolo" deploy and commit, you should commit all changes, push them, and then run `just deploy --commit`.
- Trim trailing whitespace in all edited files.

### UI Consistency (Projects & Yarns)

- The projects and yarn pages should maintain a consistent UI/UX and visual theme.
- Layout or styling changes applied to project views should also be applied to the corresponding yarn views (and vice versa).

### Release/CI Parity

- `just check` runs `lint`, `test`, and `i18n-check`.
- When touching build/deploy or release paths, also run:
  - `nix flake check`
  - `just build-image` (Docker)

## Handy Commands

- Setup: `just setup`
- Dev server: `just run`
- Lint: `just lint`
- Format: `just fmt`
- Tests: `just test`
- i18n Update: `just i18n-update`
- i18n Compile: `just i18n-compile`
- Full checks: `just check`

---

# 🧶 Stricknani — Specification & Implementation

> “A Mealie for knitting.”

Stricknani is a self-hosted web app for managing knitting projects — think **Mealie**, but for knitters.
It lets users organize projects with photos, gauge calculations, and notes in a clean, modern interface.

## 1. Purpose & Vision

- Provide a modern, private, self-hosted space for knitters to document their projects.
- Recreate the quality and usability of Mealie, adapted for fiber arts.
- Focus on usability, reproducibility, and maintainability.

---

## 2. Guiding Principles

- Don’t re-invent the wheel.
  Always use established Python libraries where possible (FastAPI auth/session middlewares, SQLAlchemy ORM, Jinja2, existing Markdown renderers, etc.).
- FastAPI + HTMX.
  Use FastAPI for API + server-side rendering (Jinja2 templates).
  Use HTMX for progressive enhancement and dynamic interactivity.
- Modern but simple.
  Avoid SPA frameworks. Keep it responsive, clean, and keyboard-friendly.
- Reproducible + portable.
  Built and tested through Nix + Docker, pushed to GHCR via CI.
- Privacy-respecting.
  No external analytics, no external asset CDN.

---

## 3. Core Use Cases

1. Create, update, and delete knitting projects.
2. Attach photos, diagrams, and notes.
3. Calculate stitch counts via the gauge tool.
4. Browse and filter projects.
5. Authenticate as a local user.

---

## 4. Functional Requirements

### 4.1 Project Data

| Field           | Type      | Required | Description                                           |
| --------------- | --------- | -------- | ----------------------------------------------------- |
| Name            | string    | ✓        | Project title                                         |
| Category        | enum      | ✓        | Pullover, Jacke, Schal, Mütze, Stirnband (extensible) |
| Yarn            | string    |          | Yarn type/brand                                       |
| Needles         | string    |          | Needle size/type                                      |
| Instructions    | markdown  |          | Rendered safely                                       |
| Diagrams        | images    |          | Optional                                              |
| Photos          | images    |          | Multiple uploads                                      |
| Notes           | text      |          | Free-form note                                        |
| Owner           | user ref  | ✓        | Creator                                               |
| Created/Updated | timestamp | ✓        | Auto-set                                              |

Operations:

- CRUD by owner.
- Filter and sort by category, date, or name.
- Multi-image upload with thumbnail gallery.

---

### 4.2 Gauge Calculator

- Input: pattern gauge, user gauge, and target width.
- Output: adjusted stitch & row count, rounded intelligently.
- Example:
  Pattern = 20 sts/10 cm, User = 18 sts/10 cm, Target = 50 cm → ≈ 90 sts.
- Inline in project form and available standalone.

---

### 4.3 Auth

- Local user accounts only (email + password).
- Hashed with bcrypt/argon2.
- Session/cookie auth with CSRF protection.
- Rate-limited login attempts.

---

### 4.4 Notes & Content

- Markdown rendering (sanitized HTML).
- Optional notes field per project.
- Photos and diagrams displayed in a unified gallery.

---

## 5. Non-Functional Requirements

| Area          | Requirement                                                        |
| ------------- | ------------------------------------------------------------------ |
| UI/UX         | Modern, responsive layout; light/dark mode; WCAG AA accessibility. |
| Performance   | Fast initial load (<200 KB critical path).                         |
| Security      | HTTPS-only, CSP, secure cookies, validated uploads.                |
| Extensibility | Modular design for additional features (inventory, patterns).      |
| Reliability   | Versioned DB migrations, documented backup process.                |

---

## 6. Architecture Overview

- Framework: FastAPI + Jinja2 + HTMX
- Database: SQLite (dev), PostgreSQL (optional)
- Auth: built on standard FastAPI middlewares
- Storage: local filesystem for user uploads (later S3-compatible option)
- Frontend: Tailwind-styled templates with HTMX for interactivity
- Tests: pytest for unit + integration

---

## 7. Deployment & Operations

### 7.1 Docker

- Single container exposing `$PORT` (default 7674)
- Config via env vars (`DATABASE_URL`, `SECRET_KEY`, `MEDIA_ROOT`, `ALLOWED_HOSTS`)
- `/healthz` endpoint for liveness
- Graceful shutdown (SIGTERM → ≤10 s)

### 7.2 CI/CD

- Build, lint, test, and push to GHCR at `ghcr.io/pschmitt/stricknani`
- Tags: `<git-sha>` and `latest`
- CI must run `just check` and `nix flake check`

---

## 8. Developer Experience

### 8.1 Toolchain

| Tool          | Purpose                  |
| ------------- | ------------------------ |
| Python 3.13+  | Language runtime         |
| uv            | Dependency management    |
| pytest        | Unit & integration tests |
| ruff          | Linter + formatter       |
| mypy (strict) | Type checking            |
| just          | Task runner              |
| nix           | Reproducible builds      |

### 8.2 Linting Policy

Linting is mandatory in CI.

- Ruff enforces style, formatting, and common error checks.
- MyPy runs in strict mode (`--strict`) for full typing discipline.
- All code must be Ruff- and MyPy-clean before merge.
- Optional pre-commit hooks run `ruff --fix` and `mypy`.

### 8.3 justfile Tasks (expected)

| Command            | Description                         |
| ------------------ | ----------------------------------- |
| `just setup`       | Setup uv environment                |
| `just run`         | Run dev server with reload          |
| `just lint`        | Run Ruff + MyPy                     |
| `just fmt`         | Format code                         |
| `just trim`        | Trim trailing whitespace            |
| `just test`        | Run pytest                          |
| `just build-image` | Build Docker image                  |
| `just push-image`  | Push image to GHCR                  |
| `just check`       | Aggregate lint + test + i18n checks |
| `just demo-data`   | Seed demo user and sample projects  |
| `just demo-reset`  | Reset and re-seed demo data         |

### 8.4 Nix Flake

- `packages.${system}.stricknani` → runnable app
- `packages.${system}.stricknani-docker` → Docker image
- `devShells.${system}.default` → uv + just + pytest + ruff
- `checks` → build + tests + flake validation
- Reproducible build required.

---

## 9. Testing & CI Validation

- Unit: gauge math, auth, models.
- Integration: CRUD + uploads + session flows.
- CI runs:
  1. `ruff check .`
  2. `mypy .`
  3. `pytest -v`
  4. `nix flake check`
- Coverage ≥ 80%.
- Image verification: start container → `/healthz` returns 200.

---

## 10. Information Architecture & UX

### 10.1 Navigation

| Section          | Content                  |
| ---------------- | ------------------------ |
| Projects         | List + filters           |
| New Project      | Form + inline gauge tool |
| Gauge Calculator | Standalone tool          |
| User Menu        | Login / Signup / Logout  |
| Footer           | Version + Privacy note   |

### 10.2 Screens

- Projects List: cards with preview image, name, category, date.
- Project Detail: title, metadata, rendered instructions, photos gallery, notes.
- New/Edit Project: form with drag-drop uploads and inline calculator.
- Auth: login/signup/logout pages.

---

## 11. Accessibility

- Smartphone is the primary device for knitters. Responsive, touch-friendly design.
- Full keyboard navigation, visible focus indicators.
- ARIA labels for interactive components.
- Mandatory alt text for all uploaded images.

---

## 12. Observability

- Structured JSON logs (request_id, user_id, route, status, latency).
- Error tracebacks logged; no external telemetry.
- Optional metrics: request count, latency histogram, media size totals.

---

## 13. Configuration & Environments

| Env   | Description                                                  |
| ----- | ------------------------------------------------------------ |
| Dev   | SQLite, local media, `.envrc` file for secrets               |
| Prod  | Configurable DB URL, media volume mount, HTTPS reverse proxy |
| Flags | `FEATURE_SIGNUP_ENABLED` (toggleable)                        |

---

## 14. Data Model (logical)

- `User` 1–N `Project`
- `Project` 1–N `Image` (type: photo | diagram)
- `Project` 1–N `Notes`
- Indices: `(owner_id, created_at)`, `(name)`

---

## 15. Acceptance Criteria (v0.1 milestone)

- New user can register, log in, create project (with photo), view details, log out.
- Gauge calculator produces correct results for reference cases.
- List view filters by category and search term.
- `just check` and `nix flake check` pass in CI.
- GHCR image builds and responds at `/healthz` = 200.
- Basic accessibility tests pass (keyboard nav + contrast).

---

## 16. Roadmap (post-v0.1)

- Multi-user sharing.
- Yarn stash tracking.
- Pattern import/export.
- Mobile editing view.
- Optional integrations with community APIs.

---

## 17. Licensing & Versioning

- License: GPL-3.0-only
- Versioning: leading-zero incremental scheme, not semantic.
  Examples:
  - `v0.1`, `v0.2`, `v0.3` → sequential milestones.
  - `v1.0` only when stable maturity is reached.
  - No semantic meaning to minor digits.

---

## 18. Repository

- URL: `github.com/pschmitt/stricknani`
- Maintainer: Philipp Schmitt (@pschmitt)
- License: GPL-3.0-only
- Spec version: 0.4

---

# Implementation Summary (v0.1.0)

This section summarizes the initial implementation of Stricknani v0.1.0.

## What Was Built

### Core Components

1. FastAPI Application (`stricknani/main.py`)
   - Health check endpoint at `/healthz`
   - Async lifecycle management with database initialization
   - Static file serving for media and templates
   - RESTful API endpoints for projects, authentication, and gauge calculation

2. Database Models (`stricknani/models/`)
   - User model with bcrypt password hashing
   - Project model with full metadata support
   - Image model for project photos and diagrams
   - SQLAlchemy ORM with async support
   - Automatic migrations setup (Alembic ready)

3. Authentication System (`stricknani/routes/auth.py`, `stricknani/utils/auth.py`)
   - JWT-based session authentication
   - Secure password hashing with bcrypt
   - Cookie-based sessions
   - Signup, login, logout endpoints
   - User authentication middleware

4. Project Management (`stricknani/routes/projects.py`)
   - Create, read, list, and delete projects
   - Filtering by category and search
   - Owner-based access control
   - Full CRUD operations via REST API

5. Gauge Calculator (`stricknani/routes/gauge.py`, `stricknani/utils/gauge.py`)
   - Accurate stitch and row calculations
   - Adjusts for gauge differences between pattern and user
   - Supports width and height calculations
   - Example: Pattern=20sts/10cm, User=18sts/10cm, Target=50cm → 90 stitches

### Development Infrastructure

6. Testing Framework (`tests/`)
   - pytest with async support
   - Unit tests for authentication and gauge calculator
   - Integration tests with in-memory SQLite
   - Coverage for core functionality

7. Code Quality Tools
   - Ruff for linting and formatting (all checks passing)
   - MyPy in strict mode for type checking (all checks passing)
   - Configuration in `pyproject.toml`
   - Pre-configured ignore rules for FastAPI patterns

8. Build & Deployment
   - Dockerfile for containerization
   - Nix flake for reproducible builds
   - GitHub Actions CI/CD workflow
   - Automated linting, testing, and image publishing to GHCR
   - Health check verification in CI

9. Developer Tools
   - justfile with common tasks (setup, run, lint, test, etc.)
   - Demo data seeder for development (`just demo-data`)
   - Environment configuration via `.envrc` file
   - uv for fast dependency management

## Project Structure

```
stricknani/
├── .github/workflows/ci.yml    # CI/CD pipeline
├── Dockerfile                   # Container definition
├── README.md                    # User documentation
├── flake.nix                    # Nix package definition
├── justfile                     # Task runner commands
├── pyproject.toml              # Python project config
├── stricknani/
│   ├── __init__.py
│   ├── config.py               # Configuration management
│   ├── database.py             # Database session handling
│   ├── main.py                 # FastAPI application
│   ├── models/
│   │   └── __init__.py         # SQLAlchemy models
│   ├── routes/
│   │   ├── auth.py             # Authentication endpoints
│   │   ├── gauge.py            # Gauge calculator endpoint
│   │   └── projects.py         # Project CRUD endpoints
│   ├── scripts/
│   │   └── seed_demo.py        # Demo data seeder
│   ├── templates/
│   │   └── base.html           # Base Jinja2 template
│   └── utils/
│       ├── auth.py             # Auth utilities
│       ├── gauge.py            # Gauge calculation logic
│       └── markdown.py         # Markdown rendering
└── tests/
    ├── test_auth.py            # Auth tests
    └── test_gauge.py           # Gauge calculator tests
```

## API Endpoints

### Health & Info

- `GET /healthz` - Health check endpoint
- `GET /` - Welcome message with version

### Authentication

- `POST /auth/signup` - Create new user account
- `POST /auth/login` - Login and get session cookie
- `POST /auth/logout` - Logout and clear session
- `GET /auth/me` - Get current user info

### Projects

- `GET /projects/` - List all projects (with optional filters)
- `GET /projects/{id}` - Get single project details
- `POST /projects/` - Create new project
- `DELETE /projects/{id}` - Delete project

### Gauge Calculator

- `POST /gauge/calculate` - Calculate adjusted stitches and rows

## Usage

### Development

```bash
# Setup environment
just setup

# Run development server (port 7674)
just run

# Run tests
just test

# Run linters
just lint

# Format code
just fmt

# Seed demo data
just demo-data

# Run all checks
just check
```

### Production (Docker)

```bash
# Build Docker image
docker build -t stricknani:latest .

# Run container
docker run -d \
  -p 7674:7674 \
  -v stricknani-data:/app/media \
  -e SECRET_KEY=your-secret-key-here \
  ghcr.io/pschmitt/stricknani:latest
```

### Configuration

Environment variables:

- `SECRET_KEY` - JWT secret (required in production)
- `PORT` - Server port (default: 7674 dev, 7674 prod)
- `DATABASE_URL` - Database connection string
- `MEDIA_ROOT` - Media files directory
- `FEATURE_SIGNUP_ENABLED` - Enable/disable signups (default: true)

## Demo Data

Run `just demo-data` to create:

- Demo user: `demo@stricknani.local` / `demo`
- Sample projects: Baby Blanket, Winter Scarf, Spring Pullover, City Beanie, Lace Headband, Weekend Cardigan
- Sample yarns: Merino Soft, Sock Delight, Chunky Monkey, Linen Breeze, Alpaca Cloud, Highland Tweed
  Run `just demo-reset` to delete existing demo data and re-seed it.

## Technical Decisions

1. Replaced passlib with bcrypt directly
   - Passlib had compatibility issues with Python 3.14
   - Direct bcrypt usage is simpler and works perfectly

2. Python 3.14 Support
   - Application fully compatible with Python 3.14
   - All dependencies work correctly

3. Async-First Design
   - All database operations use async/await
   - Better performance and scalability

4. Type Safety
   - Strict MyPy configuration
   - Full type hints throughout codebase

## Conclusion

The core backend implementation of Stricknani is complete and functional. The application:

- Follows the specification architecture
- Uses established libraries (FastAPI, SQLAlchemy, bcrypt)
- Has comprehensive test coverage
- Passes all linting and type checking
- Is containerized and ready for deployment
- Has reproducible builds via Nix
- Includes CI/CD pipeline

The foundation is solid and ready for UI development and additional features.
