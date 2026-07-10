# Build the static Tailwind CSS bundle (scans templates + static/js for
# utility classes). This replaces the old runtime/browser Tailwind JIT
# script - the served app never loads a Tailwind CDN/runtime script.
FROM debian:bookworm-slim AS cssbuild

WORKDIR /build

RUN apt-get update \
  && apt-get install -y --no-install-recommends ca-certificates curl \
  && rm -rf /var/lib/apt/lists/*

# Pinned standalone Tailwind CLI binary (a build-time tool, not a
# browser-loaded CDN asset - see AGENTS.md's vendoring rules). Keep this in
# sync with the tailwindcss_4 version used by flake.nix/nix/package.nix where
# practical.
ARG TAILWINDCSS_VERSION=4.1.14
ARG TAILWINDCSS_SHA256=bc34c301b080b6e6b98ed24118419833f966f6f347e556945d6557d36a44a56e
RUN curl -fsSL -o /usr/local/bin/tailwindcss \
      "https://github.com/tailwindlabs/tailwindcss/releases/download/v${TAILWINDCSS_VERSION}/tailwindcss-linux-x64" \
    && echo "${TAILWINDCSS_SHA256}  /usr/local/bin/tailwindcss" | sha256sum -c - \
    && chmod +x /usr/local/bin/tailwindcss

COPY stricknani/templates ./stricknani/templates
COPY stricknani/static ./stricknani/static

RUN tailwindcss \
      -i stricknani/static/css/tailwind.input.css \
      -o stricknani/static/css/tailwind.css \
      --minify

FROM python:3.14-slim

WORKDIR /app

# `pdftoppm` is used for best-effort PDF attachment thumbnails.
RUN apt-get update \
  && apt-get install -y --no-install-recommends poppler-utils \
  && rm -rf /var/lib/apt/lists/*

# Install uv
RUN pip install uv

# Copy project files
COPY README.md ./
COPY pyproject.toml ./
COPY stricknani ./stricknani

# Overwrite with the prebuilt static CSS bundle from the cssbuild stage,
# regardless of what (if anything) is present in the build context.
COPY --from=cssbuild /build/stricknani/static/css/tailwind.css ./stricknani/static/css/tailwind.css

# Install dependencies
RUN uv pip install --system -e .

# Create media directory
RUN mkdir -p /app/media

# Expose port
EXPOSE 7674

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:7674/healthz')"

# Run the application
CMD ["uvicorn", "stricknani.main:app", "--host", "0.0.0.0", "--port", "7674"]
