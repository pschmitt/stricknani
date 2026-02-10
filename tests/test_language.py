"""Tests for language detection."""

from pathlib import Path

import pytest
from babel.messages.pofile import read_po
from starlette.requests import Request

from stricknani.main import app
from stricknani.web.templating import get_language, render_template, templates

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
        "app": app,
        "router": app.router,
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


@pytest.mark.asyncio
async def test_render_template_uses_request_scoped_i18n() -> None:
    initial_global_gettext = templates.env.globals.get("_")

    en_request = _make_request(cookie="language=en")
    de_request = _make_request(cookie="language=de")

    en_response = await render_template(
        "errors/404.html",
        en_request,
        context={"current_user": None},
    )
    de_response = await render_template(
        "errors/404.html",
        de_request,
        context={"current_user": None},
    )

    en_body = bytes(en_response.body).decode("utf-8")
    de_body = bytes(de_response.body).decode("utf-8")
    assert "Page Not Found" in en_body
    assert "Seite nicht gefunden" in de_body

    # Rendering must not mutate global translator functions per request.
    assert templates.env.globals.get("_") is initial_global_gettext
