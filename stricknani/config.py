"""Configuration management for Stricknani."""

import os
import secrets
from pathlib import Path
from typing import Literal, cast

from dotenv import load_dotenv

# Load environment variables from .env file
# Look for .env in the repository root (parent of stricknani package)
_config_dir = Path(__file__).parent.parent
_env_file = _config_dir / ".env"
# Explicit environment variables (including the ephemeral keys supplied by
# `just run`) take precedence over values in `.env`.
load_dotenv(_env_file, override=False)

# Sentinel default for SECRET_KEY. If the running config still carries this
# value in production we refuse to start (see Config.validate_secrets).
_DEFAULT_SECRET_KEY = "dev-secret-key-change-in-production"


class Config:
    """Application configuration."""

    # Application
    SECRET_KEY: str = os.getenv("SECRET_KEY", _DEFAULT_SECRET_KEY)
    # Whether CSRF_SECRET_KEY was explicitly provided via the environment. When
    # unset we fall back to a random per-process key (fine for dev/tests, but
    # rejected in production by validate_secrets since it breaks across
    # restarts / multiple workers).
    _CSRF_SECRET_KEY_FROM_ENV: bool = bool(os.getenv("CSRF_SECRET_KEY"))
    CSRF_SECRET_KEY: str = os.getenv("CSRF_SECRET_KEY") or secrets.token_urlsafe(32)
    PORT: int = int(os.getenv("PORT", "7674"))
    DEBUG: bool = os.getenv("DEBUG", "false").lower() == "true"
    AUTO_RELOAD: bool = os.getenv("AUTO_RELOAD", "false").lower() in {
        "1",
        "true",
        "yes",
        "on",
    }
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
    # Uploaded source files and images are read in bounded chunks. Keep the
    # default high enough for a pattern PDF while preventing an unbounded
    # request body from being copied into process memory.
    MAX_UPLOAD_BYTES: int = int(os.getenv("MAX_UPLOAD_BYTES", str(25 * 1024 * 1024)))

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
    # SSRF guard: allow imports to resolve to private/loopback hosts. Secure by
    # default (False); self-hosters can opt in for a trusted LAN import source.
    ALLOW_PRIVATE_IMPORT_HOSTS: bool = (
        os.getenv("ALLOW_PRIVATE_IMPORT_HOSTS", "false").lower() == "true"
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
    AI_PROVIDER: str = os.getenv("AI_PROVIDER", "openai")
    AI_API_KEY: str | None = os.getenv("AI_API_KEY")
    AI_BASE_URL: str | None = os.getenv("AI_BASE_URL")
    AI_MODEL: str | None = os.getenv("AI_MODEL")
    OPENAI_API_KEY: str | None = os.getenv("OPENAI_API_KEY")
    OPENROUTER_API_KEY: str | None = os.getenv("OPENROUTER_API_KEY")
    GROQ_API_KEY: str | None = os.getenv("GROQ_API_KEY")

    # Sentry
    SENTRY_DSN_BACKEND: str | None = os.getenv("SENTRY_DSN_BACKEND")
    SENTRY_DSN_FRONTEND: str | None = os.getenv("SENTRY_DSN_FRONTEND")
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

    # Auth rate limiting (T69): per-IP and per-account attempt caps on
    # login/signup, enforced by an in-memory sliding-window limiter.
    RATE_LIMIT_LOGIN_MAX_ATTEMPTS: int = int(
        os.getenv("RATE_LIMIT_LOGIN_MAX_ATTEMPTS", "5")
    )
    RATE_LIMIT_LOGIN_WINDOW_SECONDS: int = int(
        os.getenv("RATE_LIMIT_LOGIN_WINDOW_SECONDS", "300")
    )
    RATE_LIMIT_SIGNUP_MAX_ATTEMPTS: int = int(
        os.getenv("RATE_LIMIT_SIGNUP_MAX_ATTEMPTS", "5")
    )
    RATE_LIMIT_SIGNUP_WINDOW_SECONDS: int = int(
        os.getenv("RATE_LIMIT_SIGNUP_WINDOW_SECONDS", "3600")
    )

    # Initial admin bootstrap
    INITIAL_ADMIN_EMAIL: str | None = os.getenv(
        "INITIAL_ADMIN_EMAIL", os.getenv("INITIAL_ADMIN_USERNAME")
    )
    INITIAL_ADMIN_PASSWORD: str | None = os.getenv("INITIAL_ADMIN_PASSWORD")

    @classmethod
    def validate_secrets(cls) -> None:
        """Fail fast on insecure secret configuration (T53).

        In production (non-DEBUG, non-TESTING) refuse to start if the signing
        secrets are unset or still carry their insecure defaults. This runs at
        application startup (lifespan) so tests, which run with TESTING=True,
        are unaffected.
        """
        if cls.DEBUG or cls.TESTING:
            return

        problems: list[str] = []
        if not cls.SECRET_KEY or cls.SECRET_KEY == _DEFAULT_SECRET_KEY:
            problems.append(
                "SECRET_KEY is unset or uses the insecure default; "
                "set SECRET_KEY to a strong random value"
            )
        if not cls._CSRF_SECRET_KEY_FROM_ENV:
            problems.append(
                "CSRF_SECRET_KEY is unset; set it to a strong random value "
                "(a per-process fallback breaks CSRF across restarts/workers)"
            )
        if problems:
            raise RuntimeError(
                "Refusing to start with insecure secret configuration: "
                + "; ".join(problems)
            )

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
