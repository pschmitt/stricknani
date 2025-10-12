"""Internationalization utilities."""

import os
from pathlib import Path
from typing import Any

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
    
    if translations_path.exists():
        return Translations.load(str(LOCALES_DIR), [language])
    
    # Return null translations if file doesn't exist
    return Translations.load(str(LOCALES_DIR), [])


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
    env.install_gettext_translations(translations)  # type: ignore
