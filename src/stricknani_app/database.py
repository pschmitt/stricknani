from __future__ import annotations

from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, sessionmaker

DATABASE_PATH = Path(__file__).resolve().parent.parent / "app.db"
SQLALCHEMY_DATABASE_URL = f"sqlite:///{DATABASE_PATH}"

db_engine = create_engine(
    SQLALCHEMY_DATABASE_URL, connect_args={"check_same_thread": False}
)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=db_engine)


class Base(DeclarativeBase):
    """Base declarative class for SQLAlchemy models."""


def init_database() -> None:
    from . import models  # noqa: F401 - ensures models are registered

    Base.metadata.create_all(bind=db_engine)


@contextmanager
def session_scope() -> Iterator[SessionLocal]:
    session = SessionLocal()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()
