"""Alembic environment configuration."""

from __future__ import annotations

import logging
from logging.config import fileConfig
from pathlib import Path

from sqlalchemy import engine_from_config, pool

from alembic import context
from stricknani.config import config as app_config
from stricknani.models import Base

# Interpret the config file for Python logging.
if context.config.config_file_name is not None:
    fileConfig(context.config.config_file_name, disable_existing_loggers=False)

logger = logging.getLogger(__name__)

target_metadata = Base.metadata

def _to_sync_url(url: str) -> str:
    if url.startswith("sqlite+aiosqlite:"):
        url = url.replace("sqlite+aiosqlite:", "sqlite:", 1)
    elif url.startswith("postgresql+asyncpg:"):
        url = url.replace("postgresql+asyncpg:", "postgresql:", 1)

    if url.startswith("sqlite:///"):
        db_path = Path(url.replace("sqlite:///", "", 1)).expanduser().resolve()
        return f"sqlite:///{db_path}"

    return url

def run_migrations_offline() -> None:
    """Run migrations in 'offline' mode."""
    url = context.get_x_argument(as_dictionary=True).get(
        "url", _to_sync_url(app_config.DATABASE_URL)
    )
    context.configure(url=url, target_metadata=target_metadata, literal_binds=True)

    with context.begin_transaction():
        context.run_migrations()

def run_migrations_online() -> None:
    """Run migrations in 'online' mode."""
    config = context.config
    sync_url = _to_sync_url(app_config.DATABASE_URL)
    config.set_main_option("sqlalchemy.url", sync_url)

    connectable = engine_from_config(
        config.get_section(config.config_ini_section),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )

    with connectable.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata)

        with context.begin_transaction():
            context.run_migrations()


def run_migrations() -> None:
    if context.is_offline_mode():
        run_migrations_offline()
    else:
        run_migrations_online()


run_migrations()
