# Rendered contrast check

This is a bounded synthetic-browser check, not a full WCAG audit. The runner
[`web_contrast_browser.py`](../ci/stricknani/web_contrast_browser.py) passed
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

Run from the repository checkout with an existing Playwright environment and
its matching browser cache. Keep the private fixture directory new and outside
the source tree:

```sh
python3 -m ci.stricknani.web_contrast_browser \
  --browser-cache <matched-playwright-browser-cache> \
  --binary <frozen-rust-server> \
  --web-root <frozen-web-root> \
  --private-dir <new-private-run-directory>
```
