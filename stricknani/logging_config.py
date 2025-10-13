"""Logging configuration helpers for Stricknani."""

from __future__ import annotations

import logging
import os
from typing import Final


_DEFAULT_FORMAT: Final[str] = "%(asctime)s %(levelname)s %(name)s: %(message)s"
_ACCESS_FORMAT: Final[str] = '%(client_addr)s - "%(request_line)s" %(status_code)s'
_DEFAULT_DATEFMT: Final[str] = "%Y-%m-%d %H:%M:%S"


def _resolve_level(level_name: str) -> int:
    """Translate a log level string or number into a logging level."""

    value = level_name.strip()
    if value.isdigit():
        return int(value)

    numeric = getattr(logging, value.upper(), None)
    if isinstance(numeric, int):
        return numeric

    return logging.INFO


def _configure_app_logger(level: int) -> None:
    app_logger = logging.getLogger("stricknani")
    if not app_logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter(_DEFAULT_FORMAT, _DEFAULT_DATEFMT))
        app_logger.addHandler(handler)
    app_logger.setLevel(level)
    app_logger.propagate = False


def _configure_access_logger(level: int) -> None:
    access_logger = logging.getLogger("uvicorn.access")
    if not access_logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter(_ACCESS_FORMAT, None))
        access_logger.addHandler(handler)
    access_logger.setLevel(level)
    access_logger.propagate = False


def configure_logging(*, debug: bool = False) -> None:
    """Ensure Stricknani and access loggers stream to the console."""

    env_level = os.getenv("LOG_LEVEL")
    default_level = "DEBUG" if debug else "INFO"
    level = _resolve_level(env_level or default_level)

    _configure_app_logger(level)
    # Access logs should always be at least INFO to match uvicorn defaults.
    access_level = max(level, logging.INFO)
    _configure_access_logger(access_level)
