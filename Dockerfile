FROM python:3.15-slim AS build

WORKDIR /app

# scikit-image 0.26.0 has no CPython 3.15 wheel yet, so install its stable
# source distribution with a compiler in this disposable build stage.
RUN apt-get update \
  && apt-get install -y --no-install-recommends build-essential \
  && rm -rf /var/lib/apt/lists/*

# Install uv
RUN pip install uv

# Copy project files
COPY README.md ./
COPY pyproject.toml ./
COPY stricknani ./stricknani
COPY demo_assets ./demo_assets

# Install dependencies
RUN uv pip install --system -e .

# Compile gettext catalogs (.mo) at build time so they ship in the image and
# are never lazily compiled into a read-only rootfs at request time.
RUN python -m babel.messages.frontend compile -d stricknani/locales

FROM python:3.15-slim

WORKDIR /app

# `pdftoppm` is used for best-effort PDF attachment thumbnails.
RUN apt-get update \
  && apt-get install -y --no-install-recommends poppler-utils \
  && rm -rf /var/lib/apt/lists/*

# Reuse the matching Python install and application built in the first stage.
COPY --from=build /usr/local /usr/local
COPY --from=build /app /app

# Create media directory
RUN mkdir -p /app/media

# Expose port
EXPOSE 7674

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:7674/healthz')"

# Run the application
CMD ["uvicorn", "stricknani.main:app", "--host", "0.0.0.0", "--port", "7674"]
