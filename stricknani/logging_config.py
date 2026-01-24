"""Logging configuration helpers for Stricknani."""

from __future__ import annotations

import logging
import os
from typing import Final

_DEFAULT_FORMAT: Final[str] = "%(asctime)s %(levelname)s %(name)s: %(message)s"
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

    access_logger = logging.getLogger("stricknani.access")
    access_logger.setLevel(level)


def _configure_import_logger(level: int, log_path: str | None) -> None:
    import_logger = logging.getLogger("stricknani.imports")
    if log_path:
        log_dir = os.path.dirname(log_path)
        if log_dir:
            os.makedirs(log_dir, exist_ok=True)
        if not any(
            isinstance(handler, logging.FileHandler)
            for handler in import_logger.handlers
        ):
            handler = logging.FileHandler(log_path)
            handler.setFormatter(logging.Formatter(_DEFAULT_FORMAT, _DEFAULT_DATEFMT))
            import_logger.addHandler(handler)
        import_logger.propagate = False
    import_logger.setLevel(level)


def configure_logging(*, debug: bool = False) -> None:
    """Ensure Stricknani and access loggers stream to the console."""

    env_level = os.getenv("LOG_LEVEL")
    default_level = "DEBUG" if debug else "INFO"
    level = _resolve_level(env_level or default_level)

    _configure_app_logger(level)
    _configure_import_logger(level, os.getenv("IMPORT_LOG_PATH"))
