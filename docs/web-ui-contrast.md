# Rendered contrast check

This is a bounded synthetic-browser check, not a full WCAG audit. The runner
[`web_contrast_browser.py`](../ci/stricknani/web_contrast_browser.py) now passes
on the reviewed contrast-control overlay. Its frozen static tree SHA-256 is
`547c6d323caaf3121134e7df955275bfdc5a1d7a3b8f72f962464b10e2694b22`; its
manifest SHA-256 is
`e1815123bb8c21ff18168aa9f1d89268b2bb2d16a2fabbf30705697ffc44d001`. The
matching Rust server SHA-256 is
`946188cc08f1be8d417b8a06484b0695232f74c64a0abf02ab7ba00f3c50b84e`. This
static tree is included among the 65 immutable UI assets in the deployed WIP
closure `figxvr435w5lwsd09lw98mkirbbbymfk` (full source manifest
`477906d23f22b127c2f4d6bd5daa14e1f231a9cc29c89799088c69e51a817760`). The
test used synthetic records and is separate from production data; dynamically
generated HTML, version metadata, and CSP were produced by the fresh Nix build.

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

The corrected run exited 0 with 84 assessable results and 28 categories skipped
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
| Keyboard focus outline | 4.76:1 | 9.79:1 | 3:1 |

The lowest large-heading ratios were 12.92:1 in light mode and 12.81:1 in dark
mode, above the 3:1 large-text threshold. The previous pre-fix run found weak
section markers, placeholders, and editable-field borders; the new report
confirms these sampled styles now meet their thresholds on Login, Projects,
reader/editor, yarn editor, Account, and Gauge.

This result covers representative rendered samples in this Chromium fixture.
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
