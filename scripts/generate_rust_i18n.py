"""Seed missing German Rust labels and generate the runtime JSON catalog."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from babel.messages.pofile import read_po, write_po

from scripts.extract_rust_i18n import ROOT, RUST_SOURCE, rust_label_pairs_for_tree

LOCALES = ROOT / "stricknani" / "locales"
GERMAN_PO = LOCALES / "de" / "LC_MESSAGES" / "messages.po"
RUNTIME_CATALOG = ROOT / "rust" / "locales" / "messages.json"


def seed_german_catalog(
    rust_source: Path = RUST_SOURCE,
    german_po: Path = GERMAN_PO,
) -> int:
    """Fill blank/fuzzy German entries with Rust text; preserve reviewed
    translations.
    """
    labels = rust_label_pairs_for_tree(rust_source)
    with german_po.open("r", encoding="utf-8") as handle:
        catalog = read_po(handle)
    changed = 0
    for msgid, fallback in labels.items():
        message = catalog.get(msgid)
        if message is None:
            catalog.add(msgid, string=fallback)
            changed += 1
        else:
            updated = False
            if not message.string or not str(message.string).strip() or message.fuzzy:
                # Fuzzy matches are suggestions from another msgid, not a reviewed
                # translation; the source's explicit German pair is authoritative.
                message.string = fallback
                updated = True
            if "fuzzy" in message.flags:
                message.flags.discard("fuzzy")
                updated = True
            changed += int(updated)
    with german_po.open("wb") as handle:
        write_po(handle, catalog, sort_output=False)
    return changed


def german_translations(german_po: Path) -> dict[str, str]:
    with german_po.open("r", encoding="utf-8") as handle:
        catalog = read_po(handle)
    return {
        message.id: str(message.string)
        for message in catalog
        if isinstance(message.id, str)
        and message.id
        and isinstance(message.string, str)
        and message.string.strip()
        and not message.fuzzy
    }


def rust_translations(rust_source: Path, german_po: Path) -> dict[str, str]:
    labels = rust_label_pairs_for_tree(rust_source)
    catalog = german_translations(german_po)
    return {msgid: catalog.get(msgid, fallback) for msgid, fallback in labels.items()}


def generate_runtime_catalog(
    german_po: Path = GERMAN_PO,
    output_path: Path = RUNTIME_CATALOG,
    rust_source: Path = RUST_SOURCE,
) -> None:
    translations = rust_translations(rust_source, german_po)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps({"de": translations}, ensure_ascii=False, indent=2, sort_keys=True)
        + "\n",
        encoding="utf-8",
    )


def check_runtime_catalog(
    german_po: Path = GERMAN_PO,
    output_path: Path = RUNTIME_CATALOG,
    rust_source: Path = RUST_SOURCE,
) -> bool:
    labels = rust_label_pairs_for_tree(rust_source)
    translations = german_translations(german_po)
    if not labels.keys() <= translations.keys():
        return False
    expected = (
        json.dumps(
            {
                "de": {msgid: translations[msgid] for msgid in labels},
            },
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        )
        + "\n"
    )
    return output_path.exists() and output_path.read_text(encoding="utf-8") == expected


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed-german", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        if args.seed_german:
            changed = seed_german_catalog()
            print(f"Updated {changed} German Rust catalog entries.")
        if args.check:
            if check_runtime_catalog():
                print("Rust runtime catalog is current.")
                return 0
            print(
                "Rust catalog is stale; run `just i18n-update`.",
                file=sys.stderr,
            )
            return 1
        generate_runtime_catalog()
    except (OSError, SyntaxError, ValueError) as error:
        print(f"Rust i18n catalog generation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
