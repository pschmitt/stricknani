from pathlib import Path


def test_markdown_image_autocomplete_arrow_keys_scroll_selected_item() -> None:
    js = Path("stricknani/static/js/app.js").read_text(encoding="utf-8")

    # When navigating the image autocomplete with arrow keys, the highlighted
    # element must be kept visible within the scroll container.
    assert "scrollIntoView({" in js
    assert 'block: "nearest"' in js
    assert (
        "setSelectedIndex(currentAutocomplete.selectedIndex + 1, { scroll: true })"
        in js
    )
    assert (
        "setSelectedIndex(currentAutocomplete.selectedIndex - 1, { scroll: true })"
        in js
    )
