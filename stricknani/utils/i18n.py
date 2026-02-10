"""Internationalization utilities."""

from collections.abc import Callable
from contextlib import contextmanager
from contextvars import ContextVar, Token
from pathlib import Path
from typing import Any

from babel.messages.mofile import write_mo
from babel.messages.pofile import read_po
from babel.support import NullTranslations, Translations
from jinja2 import Environment

from stricknani.config import config

# Directory containing translation files
LOCALES_DIR = Path(__file__).parent.parent / "locales"
_CURRENT_LANGUAGE: ContextVar[str] = ContextVar(
    "stricknani_current_language",
    default=config.DEFAULT_LANGUAGE,
)


def get_translations(language: str) -> Translations | NullTranslations:
    """Get translations for a specific language.

    Args:
        language: Language code (e.g., 'en', 'de')

    Returns:
        Translations object
    """
    if language not in config.SUPPORTED_LANGUAGES:
        language = config.DEFAULT_LANGUAGE

    translations_path = LOCALES_DIR / language / "LC_MESSAGES"
    po_path = translations_path / "messages.po"
    mo_path = translations_path / "messages.mo"
    cache_path = (
        config.MEDIA_ROOT / "locales" / language / "LC_MESSAGES" / "messages.mo"
    )

    if po_path.exists():
        should_compile = not mo_path.exists() and not cache_path.exists()
        if not should_compile and cache_path.exists() and po_path.exists():
            should_compile = po_path.stat().st_mtime > cache_path.stat().st_mtime
        elif not should_compile and mo_path.exists():
            should_compile = po_path.stat().st_mtime > mo_path.stat().st_mtime

        if should_compile:
            try:
                target_path = mo_path
                translations_path.mkdir(parents=True, exist_ok=True)
                with po_path.open("r", encoding="utf-8") as po_file:
                    catalog = read_po(po_file)
                with target_path.open("wb") as mo_file:
                    write_mo(mo_file, catalog)
            except OSError:
                cache_path.parent.mkdir(parents=True, exist_ok=True)
                with po_path.open("r", encoding="utf-8") as po_file:
                    catalog = read_po(po_file)
                with cache_path.open("wb") as mo_file:
                    write_mo(mo_file, catalog)

    if cache_path.exists():
        return Translations.load(
            dirname=str(cache_path.parent.parent.parent),
            locales=[language],
            domain="messages",
        )

    if mo_path.exists():
        return Translations.load(
            dirname=str(LOCALES_DIR), locales=[language], domain="messages"
        )

    # Return null translations if file doesn't exist
    return NullTranslations()


def gettext(message: str, language: str | None = None) -> str:
    """Translate a message.

    Args:
        message: Message to translate
        language: Language code, defaults to DEFAULT_LANGUAGE

    Returns:
        Translated message
    """
    if language is None:
        language = config.DEFAULT_LANGUAGE

    translations = get_translations(language)
    return translations.gettext(message)


def ngettext(singular: str, plural: str, n: int, language: str | None = None) -> str:
    """Translate a message with plural forms.

    Args:
        singular: Singular form
        plural: Plural form
        n: Count
        language: Language code, defaults to DEFAULT_LANGUAGE

    Returns:
        Translated message
    """
    if language is None:
        language = config.DEFAULT_LANGUAGE

    translations = get_translations(language)
    return translations.ngettext(singular, plural, n)


def _wrap_gettext(func: Callable[..., str]) -> Callable[..., str]:
    def _translator(message: str, *args: Any, **kwargs: Any) -> str:
        text = func(message)
        if kwargs:
            try:
                return text % kwargs
            except (TypeError, ValueError):
                pass
        if args:
            try:
                return text % args
            except (TypeError, ValueError):
                pass
        return text

    return _translator


def _wrap_ngettext(func: Callable[..., str]) -> Callable[..., str]:
    def _translator(
        singular: str,
        plural: str,
        n: int,
        *args: Any,
        **kwargs: Any,
    ) -> str:
        text = func(singular, plural, n)
        if kwargs:
            try:
                return text % kwargs
            except (TypeError, ValueError):
                pass
        if args:
            try:
                return text % args
            except (TypeError, ValueError):
                pass
        return text

    return _translator


def build_i18n_functions(
    language: str | None = None,
) -> dict[str, Callable[..., str]]:
    """Build request-scoped i18n callables for template contexts."""
    if language is None:
        language = config.DEFAULT_LANGUAGE

    translations = get_translations(language)
    gettext_fn = _wrap_gettext(translations.gettext)
    return {
        "_": gettext_fn,
        "gettext": gettext_fn,
        "ngettext": _wrap_ngettext(translations.ngettext),
    }


def _build_context_i18n_functions() -> dict[str, Callable[..., str]]:
    """Build i18n callables that resolve translations from a ContextVar."""

    def _context_gettext(message: str) -> str:
        language = _CURRENT_LANGUAGE.get()
        return get_translations(language).gettext(message)

    def _context_ngettext(singular: str, plural: str, n: int) -> str:
        language = _CURRENT_LANGUAGE.get()
        return get_translations(language).ngettext(singular, plural, n)

    gettext_fn = _wrap_gettext(_context_gettext)
    return {
        "_": gettext_fn,
        "gettext": gettext_fn,
        "ngettext": _wrap_ngettext(_context_ngettext),
    }


def set_current_language(language: str) -> Token[str]:
    """Set current language in the request-local context."""
    normalized = (
        language if language in config.SUPPORTED_LANGUAGES else config.DEFAULT_LANGUAGE
    )
    return _CURRENT_LANGUAGE.set(normalized)


def reset_current_language(token: Token[str]) -> None:
    """Reset language context to previous token."""
    _CURRENT_LANGUAGE.reset(token)


@contextmanager
def language_context(language: str) -> Any:
    """Context manager that scopes the active language for template rendering."""
    token = set_current_language(language)
    try:
        yield
    finally:
        reset_current_language(token)


def install_i18n(env: Environment, language: str | None = None) -> None:
    """Install i18n functions into Jinja2 environment.

    If language is provided, install fixed-language callables.
    If omitted, install context-local callables suitable for concurrent requests.
    """
    if language is None:
        env.globals.update(_build_context_i18n_functions())
        return
    env.globals.update(build_i18n_functions(language))
