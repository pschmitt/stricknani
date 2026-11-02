# Native browser zoom and accessibility checks

## Current packaged Firefox result

The 2026-11-02 native zoom check passed against deployed application
`52221ad266d5c38124f7ca442add952055223e7b`, using web package
`/nix/store/v2dd2d6jjn2v168flw2k1yr8xvq1pvmx-stricknani-web-0.1.0`
and the matching raw server from
`/nix/store/z1v66gbfzm2ax4y4n8cjmnkr347cpsdf-stricknani-server-0.1.0`.

The private copy of
[`web_native_a11y_browser.py`](../ci/stricknani/web_native_a11y_browser.py)
had SHA-256
`329f95ca088d879aceb7aa474bf84a2f27fdc2bc06d1f89dffe7de109bb81f17`.
Playwright 1.64.1 used its matched Firefox build in a private Xvfb, Openbox
and D-Bus session, with a synthetic account and fresh browser profile.
No user desktop, existing profile or audio device was used.

The display stayed fixed at 1280×900 pixels. Native Firefox browser zoom
reached 200%; CSS viewport width changed from 1237 to 619 pixels and
`devicePixelRatio` from 1.034 to 2.069. The Projects heading, seeded project,
search field and Search button remained visible and usable, with zero
horizontal overflow. Native keyboard input searched and cleared the filter.
A trusted X11 pointer click targeted Search and produced the expected request.
The runner required both pointer targeting and the request, rather than
accepting a successful-looking click alone.

The heading's computed CSS size changed from 43.19 to 32 pixels as the layout
reflowed. Those CSS values alone do not measure physical text scaling; the
fixed display, browser zoom setting, halved CSS viewport and doubled DPR
establish native full-page zoom rather than a viewport-only emulation.

The coordinator independently checked the exit-0 receipt at
`stricknani-browser-matrix-522-20261102/receipts/native-firefox-xvfb-02.exit`
under the private build directory. The current browser matrix and runtime
setup are recorded in [web-ui-browser-check.md](web-ui-browser-check.md).

## Scope and remaining checks

This is a controlled headed-browser reflow/input check. It is not a physical
phone journey, human accessibility study or screen-reader speech test.
[Screen-reader acceptance](web-ui-screenreader.md) remains separate and open.
Chromium native 200% zoom has not been established by this result; its ordinary
responsive browser journey is a different check. WebKit's remaining print
failure investigation is recorded in the browser matrix.

An earlier Firefox check used source `6a30d04` plus then-current overlays and a
1440×1000 display. It is historical evidence only. Its stricter pointer/request
assertions have now produced the fresh passing receipt above, superseding the
previous syntax-only status.

## Reproduction

Run the private helper copy in an owned Xvfb/D-Bus/window-manager session with
the pinned Playwright browser cache. Supply `--browser firefox`, the exact
realized `--binary` and `--web-root`, and a new nonexistent `--private-dir`.
Keep runtime paths short enough for Unix sockets and reports private. Do not
change the user's browser profile, desktop, settings or display.
