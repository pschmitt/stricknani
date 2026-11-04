"""Merge Rust `tr(english, german)` literals into the Babel POT catalog."""

from __future__ import annotations

import ast
import re
import sys
from collections import defaultdict
from collections.abc import Iterable
from pathlib import Path

from babel.messages.pofile import read_po, write_po

ROOT = Path(__file__).resolve().parents[1]
RUST_SOURCE = ROOT / "src" / "web.rs"
POT_PATH = ROOT / "stricknani" / "locales" / "messages.pot"
CALL = re.compile(
    r"\btr\s*\(\s*(?P<english>\"(?:\\.|[^\"\\])*\")\s*,\s*"
    r"(?P<german>\"(?:\\.|[^\"\\])*\")\s*\)",
    re.DOTALL,
)


def rust_label_pairs(source: str) -> dict[str, str]:
    """Return unique UI msgid/fallback pairs, rejecting conflicting German text."""
    messages: dict[str, str] = {}
    for match in CALL.finditer(source):
        english = ast.literal_eval(match.group("english"))
        german = ast.literal_eval(match.group("german"))
        if (english, german) == ("en", "de"):
            continue
        if english in messages and messages[english] != german:
            raise ValueError(f"conflicting Rust translations for msgid: {english!r}")
        messages[english] = german
    return messages


def rust_source_files(source_dir: Path = ROOT / "src") -> list[Path]:
    """Return source files in a stable order for complete Rust UI extraction."""
    return sorted(source_dir.rglob("*.rs"))


def rust_label_pairs_from_files(source_paths: Iterable[Path]) -> dict[str, str]:
    """Merge labels from Rust modules and reject cross-file fallback conflicts."""
    messages: dict[str, str] = {}
    for source_path in source_paths:
        for msgid, fallback in rust_label_pairs(
            source_path.read_text(encoding="utf-8")
        ).items():
            if msgid in messages and messages[msgid] != fallback:
                raise ValueError(f"conflicting Rust translations for msgid: {msgid!r}")
            messages[msgid] = fallback
    return messages


def rust_label_pairs_for_tree(rust_source: Path = RUST_SOURCE) -> dict[str, str]:
    """Use all Rust modules by default while preserving custom-source helper calls."""
    if rust_source == RUST_SOURCE:
        return rust_label_pairs_from_files(rust_source_files())
    return rust_label_pairs(rust_source.read_text(encoding="utf-8"))


def merge_into_pot(
    source_path: Path | None = None,
    pot_path: Path = POT_PATH,
) -> int:
    """Add Rust UI msgids and source locations to the Babel POT in place."""
    lines_by_message: dict[str, list[tuple[str, int]]] = defaultdict(list)
    source_paths = [source_path] if source_path is not None else rust_source_files()
    messages = rust_label_pairs_from_files(source_paths)
    for current_path in source_paths:
        source = current_path.read_text(encoding="utf-8")
        for match in CALL.finditer(source):
            english = ast.literal_eval(match.group("english"))
            german = ast.literal_eval(match.group("german"))
            if (english, german) != ("en", "de"):
                try:
                    relative_path = current_path.relative_to(ROOT).as_posix()
                except ValueError:
                    relative_path = current_path.name
                lines_by_message[english].append(
                    (relative_path, source.count("\n", 0, match.start()) + 1)
                )

    with pot_path.open("r", encoding="utf-8") as handle:
        catalog = read_po(handle)
    for english in messages:
        existing = catalog.get(english)
        locations = lines_by_message[english]
        if existing is None:
            catalog.add(english, locations=locations)
        else:
            existing.locations = sorted(set(existing.locations).union(locations))

    with pot_path.open("wb") as handle:
        write_po(handle, catalog, sort_output=False)
    return len(messages)


def main() -> int:
    try:
        count = merge_into_pot()
    except (OSError, SyntaxError, ValueError) as error:
        print(f"Rust i18n extraction failed: {error}", file=sys.stderr)
        return 1
    print(f"Extracted {count} Rust UI messages.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
