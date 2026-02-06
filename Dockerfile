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
