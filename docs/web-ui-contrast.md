# Rendered contrast check

This is a bounded synthetic-browser check, not a full WCAG audit.

## Deployed 72b1faa photo-dialog result

A frozen Chromium gate on 2026-11-02 checked current runtime `72b1faa`,
including the yarn photo dialog. The report has 116 rows: 88 passed, 28
skipped because a sampled target was not visible, and none failed. Routes
include Login, Projects, reader, editor, yarn editor, yarn photo dialog,
Account, and Gauge. Focus-outline ratios were 5.16:1 in light mode and 9.79:1
in dark mode, with 3 CSS-pixel outlines.

The terminal receipt is
`/home/pschmitt/build/stricknani-webkit-patched-72-20261102/contrast-photo-07.exit`
(0). The JSON report is at
`/home/pschmitt/build/stricknani-webkit-patched-72-20261102/contrast-photo-07/contrast-results.json`
(SHA-256 `b8f382727c0f4deb26558456fcb206229f10fb5e42cb360d1dbce4e3799c03dc`).
The frozen wrapper asserted the resolved current-source paths and SHA-256
values for the photo runner (`e78784d6ac9f46df939fc0dcd4d1147078ad95ce79eb0aa63528de7964d6ce84`),
shared browser runner (`dcf77a2c501302c7e59214a72465646cef625dde996ef10c701694c98b3c62fa`),
fixture loader (`59b384b98be8ca0570bea46e62fe9ec256d491ed90d5ed0c44e6685e7d72341e`),
and seed helper (`1457524c7df5606dac67a9e4cb12510ede50d9e2f8092e9878e45f560768e6c3`).
It also asserted the current72 server and packaged `200.html`, `messages.json`,
and `app-sw.js` hashes listed in
[web-ui-browser-check.md](web-ui-browser-check.md). The wrapper SHA-256 is
`c409b01898cf3b3aeca80173c6c03522302e0f82fc33519ce78e98f932ba1c87`.

An earlier photo-dialog attempt emitted sampled rows but the complete command
exited 1 (`contrast-photo-02.exit`); it is not counted as a pass. Photo-07 is
the accepted current result.

## Historical deployed 522 result

The runner
`web_contrast_browser.py` (since ported to [`web/e2e/contrast.e2e.ts`](../web/e2e/contrast.e2e.ts)) passed
against the deployed 522 package. Its SHA-256 is
`c36534c5c33aab95814f59dca9037d3979306bb8b34de742a680515bb1f36f10`. The web
root was `/nix/store/v2dd2d6jjn2v168flw2k1yr8xvq1pvmx-stricknani-web-0.1.0` and
the matching raw server was
`/nix/store/z1v66gbfzm2ax4y4n8cjmnkr347cpsdf-stricknani-server-0.1.0/bin/.stricknani-server-wrapped`.
The tested package's `200.html`, `messages.json`, and `app-sw.js` SHA-256 values
are respectively `bf63f41919e0a6426e000569170ca5c66f6654492476a6f14250ccdee8d98056`,
`f5b67b45f4935e871846bca290a2b6d01665cb63cdd740bf137eb69650f4b84a`, and
`5feb0521c818a2ff9a5c6c13b7612cf94de49356828b96d1845b60eb4ec3aa31`. The
test used synthetic records and was separate from production data.

## Method

One Chromium run used a disposable synthetic account at a 1280×900 CSS
viewport. It checked signed-out Login, Projects, a seeded project reader and
editor, a seeded yarn editor, Account settings, and Gauge in both emulated light
and dark color schemes. It sampled representative headings and body text,
muted text, links, form control text, visible placeholders, and editable-control
borders. It also moved keyboard focus with Tab and measured the visible outline.

Normal text and placeholders use a 4.5:1 threshold; large text uses 3:1.
Control borders and keyboard focus outlines use 3:1. Backgrounds are composited
through transparent ancestors. Image and opacity layers that cannot be
measured safely are skipped rather than treated as passing. The private report
contains only route/category, CSS selector, computed colors, ratios, and skip
reasons; it includes no fixture text or screenshots.

## Result

The run exited 0 with 84 assessable results and 28 categories skipped
because those routes had no visible target for that sample (for example, the
reader has no editable-field border or placeholder). All 84 assessable results
passed. This includes 14 of 14 keyboard-focus samples. Every category was
assessable in each theme across the checked routes.

| Sample | Light minimum | Dark minimum | Threshold |
| --- | ---: | ---: | ---: |
| Body text | 4.85:1 | 8.17:1 | 4.5:1 |
| Muted text | 6.00:1 | 8.17:1 | 4.5:1 |
| Links | 6.51:1 | 10.35:1 | 4.5:1 |
| Control text | 6.00:1 | 8.17:1 | 4.5:1 |
| Visible placeholders | 5.74:1 | 6.86:1 | 4.5:1 |
| Editable-control borders | 3.80:1 | 4.28:1 | 3:1 |
| Keyboard focus outline | 5.16:1 | 9.79:1 | 3:1 |

The lowest large-heading ratios were 12.92:1 in light mode and 12.81:1 in dark
mode, above the 3:1 large-text threshold. The previous pre-fix run found weak
section markers, placeholders, and editable-field borders; the new report
confirms these sampled styles now meet their thresholds on Login, Projects,
reader/editor, yarn editor, Account, and Gauge.

This run used Chromium 156.0.8078.73 and exited 0. Its mode-0600 sanitized
report and exit receipt remain in the private run directory. This result covers
representative rendered samples in this Chromium fixture.
It does not establish contrast for every component, state, browser, viewport,
image, or user content, and is not a full WCAG audit. The private mode-0600 run
report retains the exact selectors, computed colors, and ratios for this frozen
artifact.

## Reproduction

Run from the repository checkout after `npm ci --prefix web`, with an existing
Playwright browser cache. Point the run at a frozen server and web root; keep
the private output directory new and outside the source tree:

```sh
STRICKNANI_SERVER_BINARY=<frozen-rust-server> \
STRICKNANI_WEB_ROOT=<frozen-web-root> \
STRICKNANI_E2E_DIR=<new-private-run-directory> \
  npm --prefix web run e2e -- e2e/contrast.e2e.ts
```
