# Web UI browser acceptance

The browser acceptance runner starts an isolated Rust fixture with a disposable
SQLite database and media directory, serves the built static UI through the
real Rust router, and exercises login, session restoration, logout, route
handling, and responsive layouts in Chromium, Firefox, and WebKit. It checks
strict CSP, expected same-origin assets, mobile navigation, 44px navigation
controls, reduced motion, and the absence of unexpected browser errors.
Fixture credentials, cookies, and response bodies are never printed or
captured.

The 2026-11-01 integrated run passed against the static build and Rust server.
It generated sanitized signed-out mobile, signed-in mobile, and post-logout
screenshots in a private temporary output directory; screenshots are not
checked into the repository.

## Deployed 522 browser matrix

The 2026-11-02 checks used the exact deployed package for checkpoint
`52221ad266d5c38124f7ca442add952055223e7b`. The web root was
`/nix/store/v2dd2d6jjn2v168flw2k1yr8xvq1pvmx-stricknani-web-0.1.0`; the raw
Rust server executable was
`/nix/store/z1v66gbfzm2ax4y4n8cjmnkr347cpsdf-stricknani-server-0.1.0/bin/.stricknani-server-wrapped`
(SHA-256 `9e4b0e376c7aa73019eb43a7bb2df748c616613fd1c816f103075ccd408d0c4f`).
The packaged `200.html`, `messages.json`, and `app-sw.js` SHA-256 values are
`bf63f41919e0a6426e000569170ca5c66f6654492476a6f14250ccdee8d98056`,
`f5b67b45f4935e871846bca290a2b6d01665cb63cdd740bf137eb69650f4b84a`, and
`5feb0521c818a2ff9a5c6c13b7612cf94de49356828b96d1845b60eb4ec3aa31`.
The committed browser runner at this revision has SHA-256
`c80f6a9e88d3134633682ee3a2d4b343d6605b619176c0be0f9c69997f11fe50` and used
Playwright 1.64.1 with its matching cached engines.

| Engine / check | Result | Evidence |
| --- | --- | --- |
| Chromium core journey | Pass | Reused exact-package `browser-run-02` receipt; exit 0. |
| Firefox core journey | Pass | Fresh exact-package `firefox-02` run; exit 0. |
| WebKit core journey | Pass | Fresh exact-package full journey, including actual print-image failure and retry; exit 0. |

The WebKit headless process initially required an EGL display. A private
1280×900 Xvfb display, Openbox and Mesa llvmpipe software rendering resolved
startup. The original print-error test then failed to intercept any image
requests behind the controlling service worker and decoded-image cache; no
image failed, and normal printing correctly continued. That was an invalid
failure-injection setup, rather than a demonstrated print implementation bug.

The reviewed runner now isolates only the failure/retry leg in a fresh browser
context with service workers blocked and nonce-busted synthetic image sources.
It requires an intercepted original-image GET, zero print calls and the
localized error. After removing the abort it requires one print call with both
images loaded and HTTP 200 responses. The normal main context keeps service
workers enabled and retains its successful-print and cache assertions.

The updated runner SHA-256 is
`e11d0f6994eea484be9faf0bfbc69cd7f688596a1a5fb245346ae9696fbdf915`.
Its complete WebKit journey exited 0 at
`stricknani-webkit-print-failure-20261102/receipts/core-webkit-01/exit`.
The changed print-failure/retry leg also exited 0 on Chromium and Firefox in
`receipts/print-failure-chromium-02/exit` and
`receipts/print-failure-firefox-02/exit` under the same private build directory.
The coordinator independently read all three terminal receipts. Ruff, format,
compilation, MyPy, 10 helper tests and 4 health tests passed. Fixture secrets,
headers, URLs and response bodies are not included in the diagnostic output.

## Firefox native 200% zoom

A private copy of the untracked native zoom helper (SHA-256
`329f95ca088d879aceb7aa474bf84a2f27fdc2bc06d1f89dffe7de109bb81f17`) ran
against the same package and synthetic Rust fixture in a private Xvfb, Openbox,
and D-Bus session. It passed at actual Firefox browser zoom 200%. The Xvfb
geometry stayed fixed at 1280 × 900 pixels; CSS viewport width changed from
1,237 to 619 px and `devicePixelRatio` from 1.034 to 2.069. The Projects
heading, seeded project, search field, and search button remained visible and
usable, with zero horizontal overflow. A trusted X11 pointer click targeted
the search button and completed the expected search request; native keyboard
input searched and cleared the filter successfully.

The heading's computed CSS font size changed from 43.19 to 32 px as the narrower
layout reflowed; these CSS values alone are not a measure of physical text
scaling. The fixed display size, 200% browser zoom setting, halved CSS viewport,
and doubled device-pixel ratio confirm the browser applied native full-page
zoom. This is a synthetic desktop-browser check, not a physical-phone,
screen-reader, or human accessibility assessment. The mode-0600 native run
report and exit-0 receipt are retained with the private run artifacts.

## SvelteKit announcer CSP hash

SvelteKit creates its `#svelte-announcer` live region after client mount. The
pinned framework template gives that accessibility element a constant inline
style attribute so it remains visually hidden while available to assistive
technology. The frontend build extracts that one exact attribute from the
installed SvelteKit source, decodes it, and hashes its value. It fails if the
expected markup is missing or ambiguous. The resulting digest is the only
entry in `style_attribute_hashes`, and the matching CSP directive uses
`style-src-attr 'unsafe-hashes'` with that exact digest. The policy does not
allow general inline styles or scripts.

The current hash is
`sha256-S8qMpvofolR8Mpjy4kQvEm7m1q8clzU4dfDH0AmvZjo=`. The browser check reads
the manifest and confirms that the served HTTP CSP contains the same exact
hash. This keeps the live announcement region accessible without weakening
the policy for other style attributes.
