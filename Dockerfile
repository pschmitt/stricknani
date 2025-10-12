FROM python:3.13-slim

WORKDIR /app

# Install uv
RUN pip install uv

# Copy project files
COPY pyproject.toml ./
COPY stricknani ./stricknani

# Install dependencies
RUN uv pip install --system -e .

# Create media directory
RUN mkdir -p /app/media

# Expose port
EXPOSE 7874

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:7874/healthz')"

# Run the application
CMD ["uvicorn", "stricknani.main:app", "--host", "0.0.0.0", "--port", "7874"]
