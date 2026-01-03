"""Configuration management for Stricknani."""

import os
from pathlib import Path
from typing import Literal, cast

from dotenv import load_dotenv

# Load environment variables from .env file
load_dotenv()


class Config:
    """Application configuration."""

    # Application
    SECRET_KEY: str = os.getenv("SECRET_KEY", "dev-secret-key-change-in-production")
    PORT: int = int(os.getenv("PORT", "7674"))
    DEBUG: bool = os.getenv("DEBUG", "false").lower() == "true"

    # Database
    DATABASE_URL: str = os.getenv("DATABASE_URL", "sqlite:///./stricknani.db")

    # Media Storage
    MEDIA_ROOT: Path = Path(os.getenv("MEDIA_ROOT", "./media"))

    # Security
    ALLOWED_HOSTS: list[str] = os.getenv("ALLOWED_HOSTS", "localhost,127.0.0.1").split(
        ","
    )
    SESSION_COOKIE_SECURE: bool = (
        os.getenv("SESSION_COOKIE_SECURE", "true").lower() == "true"
    )
    LANGUAGE_COOKIE_SECURE: bool = (
        os.getenv("LANGUAGE_COOKIE_SECURE", "true").lower() == "true"
    )
    COOKIE_SAMESITE: Literal["lax", "strict", "none"] = cast(
        Literal["lax", "strict", "none"], os.getenv("COOKIE_SAMESITE", "strict")
    )

    # Features
    FEATURE_SIGNUP_ENABLED: bool = (
        os.getenv("FEATURE_SIGNUP_ENABLED", "true").lower() == "true"
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


config = Config()
