set shell := ['bash', '-cu']

setup:
uv sync

test:
uv run pytest

run:
uv run uvicorn stricknani_app.main:app --reload
