# Native browser zoom and accessibility checks

This report records the headed browser zoom check against the frozen combined
static artifact built from source commit `6a30d04` plus the uncommitted PWA,
print, and acceptance overlays. It uses synthetic Rust fixtures. That combined
WIP candidate is now live on the isolated preview, but is not committed or
pinned; see the checkpoint for switch evidence. The runner is
[`web_native_a11y_browser.py`](../ci/stricknani/web_native_a11y_browser.py).
It creates a fresh browser profile and private temporary directories for each
run.

## Firefox result

Firefox passed a native full-page 200% zoom check in a 1440×1000 Xvfb display. The headed browser used Playwright 1.64.1 and the matching bundled Firefox. The browser zoom shortcut changed the CSS viewport from 1252 px to 626 px and `devicePixelRatio` from 1.034 to 2.069. The virtual display size stayed fixed.

At 200%, the Projects heading, seeded project, search field, and Search button remained visible and enabled, with no horizontal page overflow. The synthetic project search and clearing both succeeded by pressing Enter from the focused search field. A DPR-scaled X11 pointer click also hit the Search button and submitted the expected query. The capture probe found that Playwright's locator click did not dispatch a click in this zoomed Firefox session; the scaled X11 click did.

The browser used its native full-page zoom. The CSS font-size of the Projects heading changed from 43.19 px to 32 px as the narrower viewport selected a responsive style; the runner does not treat that CSS font-size as a text-only zoom measure.

The accepted `native-zoom-31` run recorded successful pointer targeting and
the expected search request. After that run, the helper was hardened to make
both observations mandatory acceptance assertions and to persist safe
diagnostics before failing. The stricter code has only been syntax-checked so
far; it has not yet produced a fresh browser receipt. Its current SHA-256 is
`329f95ca088d879aceb7aa474bf84a2f27fdc2bc06d1f89dffe7de109bb81f17`.

## Chromium result

Chromium remains unverified. The headed Playwright launch ended with `TargetClosedError` before the browser journey started, including when run in a private Xvfb, D-Bus, and Openbox session. No zoom or application assertions ran in Chromium.

## Scope and remaining checks

This is a synthetic headed-browser lab result, not a physical-device result. It does not test screen-reader speech output or human assistive-technology use. The screen-reader check is tracked separately; do not treat this report as completing that gate. This report does not establish a human-reviewed or phone-tested result.

For reproduction, run the helper in a private Xvfb and D-Bus session with Playwright 1.64.1 and its matched browser cache. Pass the frozen Rust server, static web root, and a new private run directory with `--binary`, `--web-root`, and `--private-dir`; use `--browser firefox` to run the accepted check or `--browser chromium` to inspect the current launch failure.
