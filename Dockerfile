# syntax=docker/dockerfile:1
FROM ghcr.io/astral-sh/uv:latest AS builder
WORKDIR /app

COPY pyproject.toml ./
RUN uv sync --python 3.14 --no-dev --frozen

COPY src ./src
COPY README.md ./README.md
COPY Justfile ./Justfile
COPY flake.nix ./flake.nix

FROM ghcr.io/astral-sh/uv:latest
WORKDIR /app
ENV PATH="/app/.venv/bin:${PATH}" \
    PYTHONUNBUFFERED=1 \
    UV_PROJECT_PATH=/app

COPY --from=builder /app/.venv /app/.venv
COPY src ./src
COPY pyproject.toml ./
COPY README.md ./
COPY flake.nix ./
COPY Justfile ./

EXPOSE 8000
CMD [".venv/bin/uvicorn", "stricknani_app.main:app", "--host", "0.0.0.0", "--port", "8000"]
