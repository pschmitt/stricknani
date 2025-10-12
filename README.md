# Project Studio

Project Studio is a FastAPI-powered workspace for capturing project ideas, categorising them, tracking tasks in a kanban-style flow, logging updates, and building a gallery of project photos. It is inspired by an analogue planning sheet and optimised for modern browsers.

## Features

- Local account registration, login, and logout with signed cookies.
- Dashboard to manage categories and launch projects.
- Project detail view with kanban board, milestone timeline, and photo gallery uploads.
- Responsive, modern UI built with handcrafted CSS.
- SQLite persistence via SQLAlchemy models.

## Development

This project uses [uv](https://docs.astral.sh/uv/latest/) to manage Python 3.14 environments and dependencies.

```bash
just setup  # installs dependencies using uv
just run    # runs the FastAPI server with auto-reload
just test   # executes the pytest suite
```

You can alternatively use the Nix flake:

```bash
nix develop
just setup
```

## Tests

```bash
uv run pytest
```

## Deployment

A Dockerfile is provided which installs dependencies with uv and serves the FastAPI app with Uvicorn. Continuous integration builds and pushes the image to GitHub Container Registry.
