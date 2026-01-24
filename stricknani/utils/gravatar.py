"""Gravatar utilities."""

import hashlib


def gravatar_url(email: str, *, size: int = 300, default: str = "identicon") -> str:
    """Return a gravatar URL for an email address."""
    normalized = email.strip().lower().encode("utf-8")
    digest = hashlib.md5(normalized).hexdigest()  # noqa: S324
    return f"https://www.gravatar.com/avatar/{digest}?s={size}&d={default}"
