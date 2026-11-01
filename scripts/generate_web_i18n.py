"""Extract literal browser UI messages and build the public locale catalog."""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

from babel.messages.catalog import Catalog, Message
from babel.messages.pofile import read_po, write_po

ROOT = Path(__file__).resolve().parents[1]
WEB_SOURCE = ROOT / "web" / "src"
LOCALES = ROOT / "stricknani" / "locales"
ENGLISH_PO = LOCALES / "en" / "LC_MESSAGES" / "messages.po"
GERMAN_PO = LOCALES / "de" / "LC_MESSAGES" / "messages.po"
RUNTIME_CATALOG = ROOT / "web" / "static" / "messages.json"
POT_PATH = LOCALES / "messages.pot"


class ExtractionError(ValueError):
    """A browser translation call does not use a supported literal key."""


def source_files(source_dir: Path = WEB_SOURCE) -> list[Path]:
    """Return Svelte and TypeScript sources in stable path order."""
    return sorted(
        path
        for path in source_dir.rglob("*")
        if path.is_file() and path.suffix in {".svelte", ".ts", ".js"}
    )


def extract_messages(source: str, filename: str = "<source>") -> dict[str, list[int]]:
    """Extract literal `t` and `msg` keys; allow dynamic `t` lookups only.

    `t` intentionally accepts a dynamic key for labels stored in typed
    navigation data. New translation declarations and ordinary UI calls use
    literal `msg`/`t` arguments so the catalog remains statically enumerable.
    """
    messages: dict[str, list[int]] = defaultdict(list)
    index = 0
    while index < len(source):
        if source.startswith("//", index):
            newline = source.find("\n", index + 2)
            index = len(source) if newline == -1 else newline + 1
            continue
        if source.startswith("/*", index):
            end = source.find("*/", index + 2)
            if end == -1:
                raise ExtractionError(f"{filename}: unterminated block comment")
            index = end + 2
            continue
        char = source[index]
        if char in "'\"`":
            _, index = _read_quoted(source, index, filename)
            continue
        if not _identifier_start(char):
            index += 1
            continue

        start = index
        index += 1
        while index < len(source) and _identifier_continue(source[index]):
            index += 1
        name = source[start:index]
        if name not in {"t", "msg"}:
            continue
        if name == "msg" and _is_function_declaration(source, start):
            continue
        open_paren = index
        while open_paren < len(source) and source[open_paren].isspace():
            open_paren += 1
        if open_paren >= len(source) or source[open_paren] != "(":
            continue
        argument = open_paren + 1
        while argument < len(source) and source[argument].isspace():
            argument += 1
        if argument >= len(source) or source[argument] not in "'\"":
            if name == "msg":
                line = source.count("\n", 0, start) + 1
                raise ExtractionError(
                    f"{filename}:{line}: msg() requires a literal English key"
                )
            continue

        message, after_literal = _read_quoted(source, argument, filename)
        end_argument = after_literal
        while end_argument < len(source) and source[end_argument].isspace():
            end_argument += 1
        if end_argument < len(source) and source[end_argument] not in ",)":
            if name == "msg":
                line = source.count("\n", 0, start) + 1
                raise ExtractionError(
                    f"{filename}:{line}: msg() requires a literal English key"
                )
            continue
        if not message.strip():
            line = source.count("\n", 0, start) + 1
            raise ExtractionError(f"{filename}:{line}: translation key is empty")
        messages[message].append(source.count("\n", 0, start) + 1)
        index = after_literal
    return dict(messages)


def _read_quoted(source: str, start: int, filename: str) -> tuple[str, int]:
    quote = source[start]
    if quote == "`":
        index = start + 1
        while index < len(source):
            if source[index] == "\\":
                index += 2
            elif source[index] == "`":
                return "", index + 1
            else:
                index += 1
        raise ExtractionError(f"{filename}: unterminated template literal")

    result: list[str] = []
    index = start + 1
    while index < len(source):
        char = source[index]
        if char == quote:
            return "".join(result), index + 1
        if char in "\r\n":
            raise ExtractionError(f"{filename}: newline in quoted translation key")
        if char != "\\":
            result.append(char)
            index += 1
            continue
        index += 1
        if index >= len(source):
            break
        escaped = source[index]
        escapes = {
            "b": "\b",
            "f": "\f",
            "n": "\n",
            "r": "\r",
            "t": "\t",
            "v": "\v",
            "0": "\0",
        }
        if escaped in escapes:
            result.append(escapes[escaped])
            index += 1
        elif escaped in "'\"\\":
            result.append(escaped)
            index += 1
        elif escaped == "x":
            digits = source[index + 1 : index + 3]
            result.append(_codepoint(digits, 2, filename))
            index += 3
        elif escaped == "u":
            if index + 1 < len(source) and source[index + 1] == "{":
                closing = source.find("}", index + 2)
                if closing == -1:
                    raise ExtractionError(f"{filename}: invalid Unicode escape")
                digits = source[index + 2 : closing]
                result.append(_codepoint(digits, len(digits), filename))
                index = closing + 1
            else:
                digits = source[index + 1 : index + 5]
                result.append(_codepoint(digits, 4, filename))
                index += 5
        elif escaped == "\n":
            index += 1
        elif escaped == "\r" and index + 1 < len(source) and source[index + 1] == "\n":
            index += 2
        else:
            # JavaScript treats unknown escapes in non-strict strings as the
            # escaped character itself. Keep extraction consistent with that.
            result.append(escaped)
            index += 1
    raise ExtractionError(f"{filename}: unterminated quoted translation key")


def _codepoint(digits: str, expected_length: int, filename: str) -> str:
    if len(digits) != expected_length or not digits:
        raise ExtractionError(f"{filename}: invalid Unicode escape")
    try:
        value = int(digits, 16)
        return chr(value)
    except (ValueError, OverflowError) as error:
        raise ExtractionError(f"{filename}: invalid Unicode escape") from error


def _identifier_start(char: str) -> bool:
    return char.isalpha() or char in "_$"


def _identifier_continue(char: str) -> bool:
    return char.isalnum() or char in "_$"


def _is_function_declaration(source: str, start: int) -> bool:
    prefix = source[max(0, start - 24) : start]
    stripped = prefix.rstrip()
    return stripped.endswith("function") and (
        len(stripped) == len("function")
        or not _identifier_continue(stripped[-len("function") - 1])
    )


def extract_tree(source_dir: Path = WEB_SOURCE) -> dict[str, list[tuple[str, int]]]:
    """Extract all literal messages, retaining sorted source locations."""
    references: dict[str, list[tuple[str, int]]] = defaultdict(list)
    for source_path in source_files(source_dir):
        try:
            relative = source_path.relative_to(ROOT).as_posix()
        except ValueError:
            relative = source_path.name
        for message, lines in extract_messages(
            source_path.read_text(encoding="utf-8"), relative
        ).items():
            references[message].extend((relative, line) for line in lines)
    return {
        message: sorted(set(locations))
        for message, locations in sorted(references.items())
    }


def _load_catalog(path: Path, locale: str) -> Catalog:
    if not path.exists():
        return Catalog(locale=locale, charset="utf-8")
    with path.open("r", encoding="utf-8") as handle:
        return read_po(handle)


def merge_into_pot(
    references: dict[str, list[tuple[str, int]]], pot_path: Path = POT_PATH
) -> int:
    """Merge browser message ids and source locations into Babel's POT."""
    catalog = _load_catalog(pot_path, "en")
    for message_id, locations in references.items():
        entry = catalog.get(message_id)
        if entry is None:
            catalog.add(message_id, locations=locations)
        else:
            entry.locations = sorted(set(entry.locations).union(locations))
    with pot_path.open("wb") as handle:
        write_po(handle, catalog, sort_output=False)
    return len(references)


def update_po_files(
    references: dict[str, list[tuple[str, int]]],
    english_po: Path = ENGLISH_PO,
    german_po: Path = GERMAN_PO,
) -> tuple[int, int]:
    """Add newly extracted ids and locations without overwriting translations."""
    changed = [False, False]
    counts = [0, 0]
    for locale_index, (path, locale) in enumerate(
        ((english_po, "en"), (german_po, "de"))
    ):
        catalog = _load_catalog(path, locale)
        for message_id, locations in references.items():
            entry = catalog.get(message_id)
            if entry is None:
                catalog.add(
                    message_id,
                    string=message_id if locale == "en" else "",
                    locations=locations,
                )
                changed[locale_index] = True
                counts[locale_index] += 1
                continue
            if locale == "en":
                if entry.fuzzy:
                    entry.string = message_id
                    entry.flags.discard("fuzzy")
                    changed[locale_index] = True
                elif not str(entry.string).strip():
                    entry.string = message_id
                    changed[locale_index] = True
            combined = sorted(set(entry.locations).union(locations))
            if combined != entry.locations:
                entry.locations = combined
                changed[locale_index] = True
        if changed[locale_index]:
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("wb") as handle:
                write_po(handle, catalog, sort_output=False)
    return counts[0], counts[1]


def translation_errors(catalog: Catalog, messages: set[str], locale: str) -> list[str]:
    errors = []
    for message_id in sorted(messages):
        entry: Message | None = catalog.get(message_id)
        if entry is None:
            errors.append(f"{locale}: missing msgid {message_id!r}")
        elif entry.fuzzy:
            errors.append(f"{locale}: fuzzy translation for {message_id!r}")
        elif not isinstance(entry.string, str) or not entry.string.strip():
            errors.append(f"{locale}: empty translation for {message_id!r}")
    return errors


def _runtime_data(
    references: dict[str, list[tuple[str, int]]],
    english: Catalog,
    german: Catalog,
) -> dict[str, dict[str, str]]:
    keys = set(references)
    return {
        "en": {key: str(english[key].string) for key in sorted(keys)},
        "de": {key: str(german[key].string) for key in sorted(keys)},
    }


def render_catalog(data: dict[str, dict[str, str]]) -> str:
    return json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def check_runtime_catalog(
    references: dict[str, list[tuple[str, int]]],
    english_po: Path = ENGLISH_PO,
    german_po: Path = GERMAN_PO,
    output_path: Path = RUNTIME_CATALOG,
) -> tuple[bool, list[str]]:
    english = _load_catalog(english_po, "en")
    german = _load_catalog(german_po, "de")
    keys = set(references)
    errors = translation_errors(english, keys, "en") + translation_errors(
        german, keys, "de"
    )
    if errors:
        return False, errors
    expected = render_catalog(_runtime_data(references, english, german))
    return output_path.exists() and output_path.read_text(
        encoding="utf-8"
    ) == expected, []


def generate_runtime_catalog(
    references: dict[str, list[tuple[str, int]]],
    english_po: Path = ENGLISH_PO,
    german_po: Path = GERMAN_PO,
    output_path: Path = RUNTIME_CATALOG,
) -> list[str]:
    english = _load_catalog(english_po, "en")
    german = _load_catalog(german_po, "de")
    keys = set(references)
    errors = translation_errors(english, keys, "en") + translation_errors(
        german, keys, "de"
    )
    if errors:
        return errors
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        render_catalog(_runtime_data(references, english, german)), encoding="utf-8"
    )
    return []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--extract", action="store_true", help="merge browser keys into the Babel POT"
    )
    mode.add_argument(
        "--update", action="store_true", help="add extracted keys to both PO catalogs"
    )
    mode.add_argument(
        "--check", action="store_true", help="check PO coverage and generated JSON"
    )
    args = parser.parse_args()
    try:
        references = extract_tree()
        if args.extract:
            count = merge_into_pot(references)
            print(f"Merged {count} web UI messages into the Babel POT.")
            return 0
        if args.update:
            english_added, german_added = update_po_files(references)
            print(f"Added {english_added} English and {german_added} German messages.")
        if args.check:
            current, errors = check_runtime_catalog(references)
            if errors:
                print("Web UI translations need attention:", file=sys.stderr)
                print("\n".join(f"- {error}" for error in errors), file=sys.stderr)
                return 1
            if current:
                print("Web UI translation catalog is current.")
                return 0
            print("Web UI catalog is stale; run `just i18n-update`.", file=sys.stderr)
            return 1
        errors = generate_runtime_catalog(references)
        if errors:
            print("Web UI translations need attention:", file=sys.stderr)
            print("\n".join(f"- {error}" for error in errors), file=sys.stderr)
            return 1
        print(f"Generated {len(references)} web UI messages.")
    except (OSError, UnicodeError, ExtractionError, ValueError) as error:
        print(f"Web UI i18n generation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
