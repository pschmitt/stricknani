"""Database session management."""

from __future__ import annotations

import asyncio
import logging
import os
import time
from collections.abc import AsyncGenerator
from pathlib import Path

from alembic import command
from alembic.config import Config as AlembicConfig
from sqlalchemy import create_engine, inspect
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from stricknani.config import config


logger = logging.getLogger(__name__)

_MIGRATION_LOCK = config.MEDIA_ROOT / ".migrations.lock"
_LOCK_TIMEOUT_SECONDS = 30.0
_LOCK_RETRY_INTERVAL = 0.1


def _to_async_url(url: str) -> str:
    if url.startswith("sqlite+aiosqlite:"):
        return url
    if url.startswith("sqlite:"):
        return url.replace("sqlite:", "sqlite+aiosqlite:", 1)
    if url.startswith("postgresql+asyncpg:"):
        return url
    if url.startswith("postgresql:"):
        return url.replace("postgresql:", "postgresql+asyncpg:", 1)
    return url


def _to_sync_url(url: str) -> str:
    if url.startswith("sqlite+aiosqlite:"):
        url = url.replace("sqlite+aiosqlite:", "sqlite:", 1)
    elif url.startswith("postgresql+asyncpg:"):
        url = url.replace("postgresql+asyncpg:", "postgresql:", 1)

    if url.startswith("sqlite:///"):
        db_path = Path(url.replace("sqlite:///", "", 1)).expanduser().resolve()
        return f"sqlite:///{db_path}"

    return url


database_url = _to_async_url(config.DATABASE_URL)

engine = create_async_engine(database_url, echo=config.DEBUG)
AsyncSessionLocal = async_sessionmaker(
    engine, class_=AsyncSession, expire_on_commit=False
)


async def get_db() -> AsyncGenerator[AsyncSession]:
    """Get database session."""
    async with AsyncSessionLocal() as session:
        yield session


async def init_db() -> None:
    """Initialize the database by applying migrations."""

    def _run_upgrade() -> None:
        sync_url = _to_sync_url(config.DATABASE_URL)
        alembic_cfg = AlembicConfig(
            str(Path(__file__).resolve().parent.parent / "alembic.ini")
        )
        alembic_cfg.set_main_option("sqlalchemy.url", sync_url)

        lock_fd: int | None = None
        try:
            try:
                lock_fd = _acquire_lock(_MIGRATION_LOCK)
            except TimeoutError as exc:  # pragma: no cover - defensive
                logger.error("Failed to acquire migration lock: %s", exc)
                raise

            engine = create_engine(sync_url)
            try:
                with engine.connect() as connection:
                    inspector = inspect(connection)
                    has_version_table = inspector.has_table("alembic_version")
                    existing_tables = [
                        name
                        for name in inspector.get_table_names()
                        if name != "alembic_version"
                    ]

                if not has_version_table and existing_tables:
                    logger.info(
                        "Stamping existing database with current Alembic head",
                    )
                    command.stamp(alembic_cfg, "head")
                else:
                    command.upgrade(alembic_cfg, "head")
            finally:
                engine.dispose()
        finally:
            if lock_fd is not None:
                _release_lock(lock_fd, _MIGRATION_LOCK)

    await asyncio.to_thread(_run_upgrade)


def _acquire_lock(lock_path: Path) -> int:
    """Acquire a simple file-based lock for migration execution."""

    lock_path.parent.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + _LOCK_TIMEOUT_SECONDS
    while True:
        try:
            return os.open(lock_path, os.O_CREAT | os.O_EXCL | os.O_RDWR)
        except FileExistsError:
            if time.monotonic() > deadline:
                raise TimeoutError("Timed out waiting for migration lock")
            time.sleep(_LOCK_RETRY_INTERVAL)


def _release_lock(fd: int, lock_path: Path) -> None:
    """Release the lock acquired with :func:`_acquire_lock`."""

    os.close(fd)
    try:
        lock_path.unlink()
    except FileNotFoundError:
        pass
