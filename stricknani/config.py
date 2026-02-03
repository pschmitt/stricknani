"""Configuration management for Stricknani."""

import os
from pathlib import Path
from typing import Literal, cast

from dotenv import load_dotenv

# Load environment variables from .envrc file
# Look for .envrc in the repository root (parent of stricknani package)
_config_dir = Path(__file__).parent.parent
_env_file = _config_dir / ".envrc"
load_dotenv(_env_file, override=True)


class Config:
    """Application configuration."""

    # Application
    SECRET_KEY: str = os.getenv("SECRET_KEY", "dev-secret-key-change-in-production")
    CSRF_SECRET_KEY: str = os.getenv("CSRF_SECRET_KEY", SECRET_KEY)
    PORT: int = int(os.getenv("PORT", "7674"))
    DEBUG: bool = os.getenv("DEBUG", "false").lower() == "true"
    TESTING: bool = (
        os.getenv("TESTING", "false").lower() == "true"
        or "PYTEST_CURRENT_TEST" in os.environ
    )

    # Database
    DATABASE_URL: str = os.getenv("DATABASE_URL", "sqlite:///./stricknani.db")

    # Media Storage
    MEDIA_ROOT: Path = Path(os.getenv("MEDIA_ROOT", "./media"))
    IMPORT_TRACE_ENABLED: bool = bool(os.getenv("IMPORT_TRACE_ENABLED"))
    IMPORT_TRACE_DIR: Path = Path(
        os.getenv("IMPORT_TRACE_DIR", str(MEDIA_ROOT / "import-traces"))
    )
    IMPORT_TRACE_MAX_CHARS: int = int(os.getenv("IMPORT_TRACE_MAX_CHARS", "12000"))

    # Security
    ALLOWED_HOSTS: list[str] = os.getenv("ALLOWED_HOSTS", "localhost,127.0.0.1").split(
        ","
    )
    SESSION_COOKIE_SECURE: bool = (
        os.getenv("SESSION_COOKIE_SECURE", "false").lower() == "true"
    )
    LANGUAGE_COOKIE_SECURE: bool = (
        os.getenv("LANGUAGE_COOKIE_SECURE", "false").lower() == "true"
    )
    COOKIE_SAMESITE: Literal["lax", "strict", "none"] = cast(
        Literal["lax", "strict", "none"], os.getenv("COOKIE_SAMESITE", "strict")
    )

    # Features
    FEATURE_SIGNUP_ENABLED: bool = (
        os.getenv("FEATURE_SIGNUP_ENABLED", "false").lower() == "true"
    )
    FEATURE_WAYBACK_ENABLED: bool = (
        os.getenv("FEATURE_WAYBACK_ENABLED", "false").lower() == "true"
    )
    FEATURE_AI_IMPORT_ENABLED: bool = (
        os.getenv("FEATURE_AI_IMPORT_ENABLED", "true").lower() == "true"
    )

    # AI/OpenAI
    OPENAI_API_KEY: str | None = os.getenv("OPENAI_API_KEY")

    # Sentry
    SENTRY_DSN: str | None = os.getenv("SENTRY_DSN")
    SENTRY_FRONTEND_DSN: str | None = os.getenv("SENTRY_FRONTEND_DSN")
    SENTRY_ENVIRONMENT: str = os.getenv("SENTRY_ENVIRONMENT", "production")
    SENTRY_TRACES_SAMPLE_RATE: float = float(
        os.getenv("SENTRY_TRACES_SAMPLE_RATE", "0")
    )
    SENTRY_FRONTEND_TRACES_SAMPLE_RATE: float = float(
        os.getenv("SENTRY_FRONTEND_TRACES_SAMPLE_RATE", str(SENTRY_TRACES_SAMPLE_RATE))
    )

    # Internationalization
    DEFAULT_LANGUAGE: str = os.getenv("DEFAULT_LANGUAGE", "de")
    SUPPORTED_LANGUAGES: list[str] = ["en", "de"]

    # Auth
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60 * 24 * 7  # 1 week
    ALGORITHM: str = "HS256"

    # Initial admin bootstrap
    INITIAL_ADMIN_EMAIL: str | None = os.getenv(
        "INITIAL_ADMIN_EMAIL", os.getenv("INITIAL_ADMIN_USERNAME")
    )
    INITIAL_ADMIN_PASSWORD: str | None = os.getenv("INITIAL_ADMIN_PASSWORD")

    @classmethod
    def ensure_media_dirs(cls) -> None:
        """Ensure media directories exist."""
        cls.MEDIA_ROOT.mkdir(exist_ok=True)
        (cls.MEDIA_ROOT / "projects").mkdir(exist_ok=True)
        (cls.MEDIA_ROOT / "users").mkdir(exist_ok=True)
        (cls.MEDIA_ROOT / "thumbnails").mkdir(exist_ok=True)
        (cls.MEDIA_ROOT / "thumbnails" / "projects").mkdir(parents=True, exist_ok=True)
        (cls.MEDIA_ROOT / "thumbnails" / "users").mkdir(parents=True, exist_ok=True)
        if cls.IMPORT_TRACE_ENABLED:
            cls.IMPORT_TRACE_DIR.mkdir(parents=True, exist_ok=True)


config = Config()
