"""Internationalization utilities."""

from collections.abc import Callable
from pathlib import Path
from babel.messages.mofile import write_mo
from babel.messages.pofile import read_po
from babel.support import Translations
from jinja2 import Environment

from stricknani.config import config

# Directory containing translation files
LOCALES_DIR = Path(__file__).parent.parent / "locales"


def get_translations(language: str) -> Translations:
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

    if po_path.exists():
        should_compile = not mo_path.exists()
        if not should_compile:
            should_compile = po_path.stat().st_mtime > mo_path.stat().st_mtime

        if should_compile:
            translations_path.mkdir(parents=True, exist_ok=True)
            with po_path.open("r", encoding="utf-8") as po_file:
                catalog = read_po(po_file)
            with mo_path.open("wb") as mo_file:
                write_mo(mo_file, catalog)

    if mo_path.exists():
        return Translations.load(
            dirname=str(LOCALES_DIR), locales=[language], domain="messages"
        )

    # Return null translations if file doesn't exist
    from babel.support import NullTranslations
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


def install_i18n(env: Environment, language: str | None = None) -> None:
    """Install i18n functions into Jinja2 environment.

    Args:
        env: Jinja2 environment
        language: Language code, defaults to DEFAULT_LANGUAGE
    """
    if language is None:
        language = config.DEFAULT_LANGUAGE

    translations = get_translations(language)

    def _wrap_gettext(func: Callable[..., str]) -> Callable[..., str]:
        def _translator(message: str, *args, **kwargs) -> str:
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
        def _translator(singular: str, plural: str, n: int, *args, **kwargs) -> str:
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

    env.globals["_"] = _wrap_gettext(translations.gettext)
    env.globals["gettext"] = env.globals["_"]
    env.globals["ngettext"] = _wrap_ngettext(translations.ngettext)
