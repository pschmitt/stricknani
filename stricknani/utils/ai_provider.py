"""Provider-agnostic AI client configuration helpers."""

from __future__ import annotations

import os
from typing import Literal, cast

AIProvider = Literal["openai", "openrouter", "groq"]

_DEFAULT_PROVIDER: AIProvider = "openai"

_DEFAULT_BASE_URLS: dict[AIProvider, str | None] = {
    "openai": None,
    "openrouter": "https://openrouter.ai/api/v1",
    "groq": "https://api.groq.com/openai/v1",
}

_DEFAULT_CHAT_MODELS: dict[AIProvider, str] = {
    "openai": "gpt-4o-mini",
    "openrouter": "openai/gpt-4o-mini",
    "groq": "llama-3.3-70b-versatile",
}

_DEFAULT_RESPONSES_MODELS: dict[AIProvider, str] = {
    "openai": "gpt-5-mini",
    # Keep providers explicit. Not all OpenAI-compatible providers implement
    # the Responses API, but OpenRouter may proxy OpenAI models.
    "openrouter": "openai/gpt-4o-mini",
    "groq": "llama-3.3-70b-versatile",
}


def resolve_ai_provider(provider: str | None = None) -> AIProvider:
    """Resolve provider from explicit value or environment."""
    raw = (provider or os.getenv("AI_PROVIDER") or _DEFAULT_PROVIDER).strip().lower()
    if raw in {"openai", "openrouter", "groq"}:
        return cast(AIProvider, raw)
    return _DEFAULT_PROVIDER


def get_ai_api_key(
    *,
    provider: AIProvider | None = None,
    api_key: str | None = None,
) -> str | None:
    """Resolve API key for provider with backwards-compatible fallbacks."""
    if api_key:
        return api_key

    resolved = resolve_ai_provider(provider)
    shared = os.getenv("AI_API_KEY")
    if shared:
        return shared

    if resolved == "openrouter":
        return os.getenv("OPENROUTER_API_KEY") or os.getenv("OPENAI_API_KEY")
    if resolved == "groq":
        return os.getenv("GROQ_API_KEY") or os.getenv("OPENAI_API_KEY")
    return os.getenv("OPENAI_API_KEY")


def get_ai_base_url(
    *,
    provider: AIProvider | None = None,
    base_url: str | None = None,
) -> str | None:
    """Resolve base URL for provider."""
    if base_url:
        return base_url
    explicit = os.getenv("AI_BASE_URL")
    if explicit:
        return explicit
    return _DEFAULT_BASE_URLS[resolve_ai_provider(provider)]


def get_default_ai_model(
    *,
    provider: AIProvider | None = None,
    api_style: Literal["chat", "responses"] = "chat",
) -> str:
    """Resolve default model by provider and endpoint style."""
    resolved = resolve_ai_provider(provider)
    if api_style == "responses":
        return _DEFAULT_RESPONSES_MODELS[resolved]
    return _DEFAULT_CHAT_MODELS[resolved]


def has_ai_api_key(provider: str | None = None) -> bool:
    """Return whether an AI key is available for the active provider."""
    return bool(get_ai_api_key(provider=resolve_ai_provider(provider)))
