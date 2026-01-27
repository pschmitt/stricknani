"""Check for missing or fuzzy translations."""

from __future__ import annotations

import sys
from pathlib import Path

from babel.messages.catalog import Message
from babel.messages.pofile import read_po

LOCALES_DIR = Path(__file__).resolve().parents[1] / "locales"


def _load_catalog(path: Path) -> list[Message]:
    with path.open("r", encoding="utf-8") as handle:
        catalog = read_po(handle)
    return list(catalog)


def _is_missing(message: Message) -> bool:
    if not message.id:
        return False
    if message.string is None:
        return True
    if isinstance(message.string, dict):
        return any(not str(value).strip() for value in message.string.values())
    if isinstance(message.string, (list, tuple)):
        return any(not str(value).strip() for value in message.string)
    return not str(message.string).strip()


def main() -> int:
    template_path = LOCALES_DIR / "messages.pot"
    de_path = LOCALES_DIR / "de" / "LC_MESSAGES" / "messages.po"

    template_messages = _load_catalog(template_path)
    de_messages = _load_catalog(de_path)

    template_ids = {message.id for message in template_messages if message.id}
    de_by_id = {message.id: message for message in de_messages if message.id}

    missing_ids = sorted(
        (msgid for msgid in template_ids if msgid not in de_by_id),
        key=str,
    )
    missing_strings = sorted(
        (
            msgid
            for msgid, message in de_by_id.items()
            if msgid in template_ids and _is_missing(message)
        ),
        key=str,
    )
    fuzzy_strings = sorted(
        (
            msgid
            for msgid, message in de_by_id.items()
            if msgid in template_ids and message.fuzzy
        ),
        key=str,
    )

    if not (missing_ids or missing_strings or fuzzy_strings):
        print("Translations look complete.")
        return 0

    if missing_ids:
        print("Missing msgids in de catalog:")
        for msgid in missing_ids:
            print(f"  - {msgid}")

    if missing_strings:
        print("Missing msgstr entries in de catalog:")
        for msgid in missing_strings:
            print(f"  - {msgid}")

    if fuzzy_strings:
        print("Fuzzy msgstr entries in de catalog:")
        for msgid in fuzzy_strings:
            print(f"  - {msgid}")

    return 1


if __name__ == "__main__":
    sys.exit(main())
