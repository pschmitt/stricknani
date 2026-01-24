"""Tests for language detection."""

from pathlib import Path

from babel.messages.pofile import read_po
from starlette.requests import Request

from stricknani.main import get_language

LOCALES_DIR = Path(__file__).resolve().parents[1] / "stricknani" / "locales"


def _make_request(
    accept_language: str | None = None, cookie: str | None = None
) -> Request:
    headers: list[tuple[bytes, bytes]] = []
    if accept_language:
        headers.append((b"accept-language", accept_language.encode("utf-8")))
    if cookie:
        headers.append((b"cookie", cookie.encode("utf-8")))
    scope = {
        "type": "http",
        "method": "GET",
        "path": "/",
        "scheme": "http",
        "headers": headers,
        "server": ("test", 80),
        "client": ("test", 1234),
    }
    return Request(scope)


def test_get_language_prefers_cookie() -> None:
    request = _make_request(
        accept_language="de-DE,de;q=0.9,en;q=0.8",
        cookie="language=en",
    )

    assert get_language(request) == "en"


def test_get_language_prefers_header_quality() -> None:
    request = _make_request(
        accept_language="en;q=0.5,de-DE;q=0.9,fr-FR;q=0.8",
    )

    assert get_language(request) == "de"


def test_get_language_falls_back_to_english() -> None:
    request = _make_request(accept_language="fr-FR,fr;q=0.9")

    assert get_language(request) == "en"


def test_de_translations_have_no_missing_entries() -> None:
    po_path = LOCALES_DIR / "de" / "LC_MESSAGES" / "messages.po"
    with po_path.open("r", encoding="utf-8") as handle:
        catalog = read_po(handle)

    missing = [
        message.id
        for message in catalog
        if message.id and (message.string is None or not str(message.string).strip())
    ]
    assert missing == []
