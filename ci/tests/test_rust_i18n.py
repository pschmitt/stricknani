"""Checks for the Rust gettext extraction and generated runtime catalog."""

from __future__ import annotations

from pathlib import Path

import pytest
from babel.messages.catalog import Catalog
from babel.messages.pofile import read_po, write_po

from scripts.extract_rust_i18n import (
    ROOT,
    rust_label_pairs,
    rust_label_pairs_for_tree,
    rust_label_pairs_from_files,
    rust_source_files,
)
from scripts.generate_rust_i18n import german_translations, seed_german_catalog


def test_rust_label_extraction_skips_language_code_pair() -> None:
    labels = rust_label_pairs(
        'let language = tr("en", "de");\n'
        'let title = tr(\n "Gauge calculator",\n "Maschenprobe berechnen"\n);'
    )

    assert labels == {"Gauge calculator": "Maschenprobe berechnen"}


def test_rust_label_extraction_rejects_conflicting_pairs() -> None:
    with pytest.raises(ValueError, match="conflicting Rust translations"):
        rust_label_pairs('tr("Yarn", "Garn"); tr("Yarn", "Wolle");')


def test_extracted_labels_have_german_catalog_entries() -> None:
    labels = rust_label_pairs_for_tree()
    translations = german_translations(
        ROOT / "stricknani" / "locales" / "de" / "LC_MESSAGES" / "messages.po"
    )

    assert labels
    assert set(labels) <= translations.keys()


def test_rust_source_scan_covers_the_whole_source_tree() -> None:
    names = {source.name for source in rust_source_files()}
    assert {"web.rs", "lib.rs", "mod.rs"} <= names


def test_cross_file_fallback_conflicts_are_rejected(tmp_path: Path) -> None:
    first = tmp_path / "one.rs"
    second = tmp_path / "two.rs"
    first.write_text('tr("Shared label", "Erste Übersetzung");', encoding="utf-8")
    second.write_text('tr("Shared label", "Zweite Übersetzung");', encoding="utf-8")

    with pytest.raises(ValueError, match="conflicting Rust translations"):
        rust_label_pairs_from_files([first, second])


def test_german_seeding_keeps_existing_translation(tmp_path: Path) -> None:
    source = tmp_path / "web.rs"
    source.write_text(
        'tr("Projects", "Projekte"); tr("Yarns", "Garne");',
        encoding="utf-8",
    )
    catalog_path = tmp_path / "messages.po"
    catalog = Catalog(locale="de")
    catalog.add("Projects", string="Projektliste")
    catalog.add("Yarns", string="")
    with catalog_path.open("wb") as handle:
        write_po(handle, catalog)

    assert seed_german_catalog(source, catalog_path) == 1

    with catalog_path.open("r", encoding="utf-8") as handle:
        updated = read_po(handle)
    assert updated["Projects"].string == "Projektliste"
    assert updated["Yarns"].string == "Garne"
