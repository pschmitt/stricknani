from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest
from babel.messages.catalog import Catalog
from babel.messages.pofile import read_po, write_po

from scripts.generate_web_i18n import (
    ExtractionError,
    check_runtime_catalog,
    extract_messages,
    generate_runtime_catalog,
    merge_into_pot,
    render_catalog,
    update_po_files,
)


def test_extracts_literal_messages_and_decodes_javascript_escapes() -> None:
    source = r"""function msg(key: string): string { return key; }
const fake = "msg('quoted fake')";
// msg('comment fake')
const single = msg('Can\'t open \\ this');
const double = t("A \"quoted\" label", locale);
const dynamic = t(item.label, locale);
"""

    assert extract_messages(source, "sample.ts") == {
        'A "quoted" label': [5],
        "Can't open \\ this": [4],
    }


def test_dynamic_msg_keys_are_rejected_but_dynamic_t_keys_are_allowed() -> None:
    with pytest.raises(ExtractionError, match=r"msg\(\) requires a literal"):
        extract_messages("msg(item.label);", "sample.svelte")

    assert extract_messages("t(item.label, locale);", "sample.ts") == {}


def test_fuzzy_or_missing_translations_block_catalog_generation(tmp_path: Path) -> None:
    references = {"Open projects": [("web/src/page.svelte", 5)]}
    english_po = tmp_path / "en.po"
    german_po = tmp_path / "de.po"
    output = tmp_path / "messages.json"

    _write_po(english_po, "en", {"Open projects": ("Open projects", set())})
    _write_po(german_po, "de", {"Open projects": ("Projekte öffnen", {"fuzzy"})})
    errors = generate_runtime_catalog(references, english_po, german_po, output)

    assert errors == ["de: fuzzy translation for 'Open projects'"]
    assert not output.exists()

    _write_po(german_po, "de", {})
    _, errors = check_runtime_catalog(references, english_po, german_po, output)
    assert errors == ["de: missing msgid 'Open projects'"]

    _write_po(german_po, "de", {"Open projects": ("", set())})
    _, errors = check_runtime_catalog(references, english_po, german_po, output)
    assert errors == ["de: empty translation for 'Open projects'"]


def test_catalog_update_preserves_existing_translation_and_adds_blank_german(
    tmp_path: Path,
) -> None:
    english_po = tmp_path / "en.po"
    german_po = tmp_path / "de.po"
    _write_po(english_po, "en", {"Projects": ("Project library", set())})
    _write_po(german_po, "de", {"Projects": ("Projektübersicht", set())})
    references = {
        "Projects": [("web/src/page.svelte", 4)],
        "Save changes": [("web/src/edit.svelte", 9)],
    }

    assert update_po_files(references, english_po, german_po) == (1, 1)
    english = _read_po(english_po)
    german = _read_po(german_po)
    assert english["Projects"].string == "Project library"
    assert german["Projects"].string == "Projektübersicht"
    assert english["Save changes"].string == "Save changes"
    assert german["Save changes"].string == ""


def test_generated_catalog_is_stable_and_stale_output_is_detected(
    tmp_path: Path,
) -> None:
    references = {
        "Yarns": [("web/src/yarns.svelte", 3)],
        "Projects": [("web/src/projects.svelte", 2)],
    }
    english_po = tmp_path / "en.po"
    german_po = tmp_path / "de.po"
    output = tmp_path / "messages.json"
    _write_po(
        english_po,
        "en",
        {"Yarns": ("Yarns", set()), "Projects": ("Projects", set())},
    )
    _write_po(
        german_po,
        "de",
        {"Yarns": ("Garne", set()), "Projects": ("Projekte", set())},
    )

    assert generate_runtime_catalog(references, english_po, german_po, output) == []
    current, errors = check_runtime_catalog(references, english_po, german_po, output)
    assert current and errors == []
    assert output.read_text(encoding="utf-8") == render_catalog(
        {
            "en": {"Projects": "Projects", "Yarns": "Yarns"},
            "de": {"Projects": "Projekte", "Yarns": "Garne"},
        }
    )

    output.write_text(json.dumps({"en": {}, "de": {}}), encoding="utf-8")
    current, errors = check_runtime_catalog(references, english_po, german_po, output)
    assert not current and errors == []


def test_pot_merge_preserves_existing_translation_through_babel_update(
    tmp_path: Path,
) -> None:
    pot_path = tmp_path / "messages.pot"
    pot = Catalog(locale="en", charset="utf-8")
    pot.add("Existing web label")
    with pot_path.open("wb") as handle:
        write_po(handle, pot)

    references = {
        "Existing web label": [("web/src/layout.svelte", 4)],
        "New web label": [("web/src/page.svelte", 9)],
    }
    assert merge_into_pot(references, pot_path) == 2

    locales = tmp_path / "locales"
    german_po = locales / "de" / "LC_MESSAGES" / "messages.po"
    _write_po(
        german_po,
        "de",
        {"Existing web label": ("Bestehender Webtext", set())},
    )
    subprocess.run(
        [
            sys.executable,
            "-m",
            "babel.messages.frontend",
            "update",
            "-i",
            str(pot_path),
            "-d",
            str(locales),
        ],
        check=True,
        capture_output=True,
        text=True,
    )

    with german_po.open("r", encoding="utf-8") as handle:
        updated = read_po(handle)
    assert updated["Existing web label"].string == "Bestehender Webtext"
    assert updated["New web label"].string == "Bestehender Webtext"
    assert updated["New web label"].fuzzy


def _write_po(
    path: Path, locale: str, entries: dict[str, tuple[str, set[str]]]
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    catalog = Catalog(locale=locale, charset="utf-8")
    for message_id, (translation, flags) in entries.items():
        catalog.add(message_id, string=translation, flags=flags)
    with path.open("wb") as handle:
        write_po(handle, catalog)


def _read_po(path: Path) -> Catalog:
    from babel.messages.pofile import read_po

    with path.open("r", encoding="utf-8") as handle:
        return read_po(handle)
