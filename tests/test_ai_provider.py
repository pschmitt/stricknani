from __future__ import annotations

import pytest

from stricknani.utils.ai_provider import (
    get_ai_api_key,
    get_ai_base_url,
    get_default_ai_model,
    has_ai_api_key,
    resolve_ai_provider,
)


def test_resolve_ai_provider_defaults_to_openai() -> None:
    assert resolve_ai_provider(None) == "openai"
    assert resolve_ai_provider("invalid-provider") == "openai"


def test_get_ai_api_key_prefers_explicit_argument(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("AI_API_KEY", "shared-key")
    monkeypatch.setenv("OPENAI_API_KEY", "openai-key")
    assert get_ai_api_key(provider="openai", api_key="explicit-key") == "explicit-key"


def test_get_ai_api_key_uses_provider_specific_env(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("AI_API_KEY", raising=False)
    monkeypatch.setenv("OPENROUTER_API_KEY", "router-key")
    monkeypatch.setenv("GROQ_API_KEY", "groq-key")
    monkeypatch.setenv("OPENAI_API_KEY", "openai-key")

    assert get_ai_api_key(provider="openrouter") == "router-key"
    assert get_ai_api_key(provider="groq") == "groq-key"
    assert get_ai_api_key(provider="openai") == "openai-key"


def test_get_ai_base_url_defaults() -> None:
    assert get_ai_base_url(provider="openai") is None
    assert get_ai_base_url(provider="openrouter") == "https://openrouter.ai/api/v1"
    assert get_ai_base_url(provider="groq") == "https://api.groq.com/openai/v1"


def test_default_model_by_provider() -> None:
    assert get_default_ai_model(provider="openai", api_style="chat") == "gpt-4o-mini"
    assert (
        get_default_ai_model(provider="openrouter", api_style="chat")
        == "openai/gpt-4o-mini"
    )
    assert (
        get_default_ai_model(provider="groq", api_style="responses")
        == "llama-3.3-70b-versatile"
    )


def test_has_ai_api_key_uses_provider_resolution(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("AI_PROVIDER", "groq")
    monkeypatch.setenv("GROQ_API_KEY", "groq-key")
    monkeypatch.delenv("AI_API_KEY", raising=False)
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    assert has_ai_api_key()
