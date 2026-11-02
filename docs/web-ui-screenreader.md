# Screen-reader verification

**Status: partial; strict native screen-reader acceptance remains open.**

## Current compiled candidate

The current native probe used the canonical public-root-pinned 72b1 package:
web root `/nix/store/gyqjfr40aiszvyg9l8lxp7i8wq2qlgjc-stricknani-web-0.1.0`,
server package `/nix/store/n65q658kcrqh0qbqkfnsds8k7m007pa8-stricknani-server-0.1.0`,
and raw server SHA-256
`460dd562505f51518d7c65fdabd199e7d27210a55574f7bc83bb428b667fcbcd`.
The embedded and standalone web roots matched across 107 files. This was an
isolated synthetic fixture using Firefox, Orca, Speech Dispatcher with dummy
output, Xvfb, and private D-Bus state; no user desktop, audio device, or live
service was used.

The strict native journey exited 1 at the Yarns navigation announcement. Native
XTest input reached the Yarns link, and the AT-SPI snapshot confirmed a focused
link named “Yarns” in the expected Firefox frame. The Orca capture contained no
speech output for that focus. Therefore the run did not reach the photo dialog
or invalid-step validation, and it is not an acceptance pass for those flows.
The strict runner SHA-256 was
`0c1d0aedf9be64bee8ea558f9879cffddfb3134b5fd3edf993991200f59cf70e`; exit
receipt:
`/home/pschmitt/build/stricknani-validation-focus-72b1faa-native-20261102/receipts/native-orca/run-full02.exit`.
The focused AT-SPI diagnostic runner SHA-256 was
`393111234acbaaf90c028b1b844068f0a2050836cc0a408db6b0eb1fc80ce55c`; its
exit receipt is
`/home/pschmitt/build/stricknani-validation-focus-72b1faa-native-20261102/receipts/native-orca/run-atspi03.exit`.
The diagnostic confirmed the same role, accessible name, active state, and
Firefox frame. It did not alter the product or relax the strict speech check.

The private Orca writer shim flushes Orca 50.2's explicit debug handle after
the original write. It changes capture timing only; it does not inject speech
or alter accessibility events. Its SHA-256 is
`5938078d3639ae2a7d3d9733aff4adf0f9ed5998504bae05ad992b6222a7f1ac`.
Earlier investigation found that Orca's writer otherwise did not flush after
each write, so action-offset checks could miss speech until a later event.

## Earlier isolated dialog and validation evidence

An earlier strict run against a static dialog candidate verified native
navigation and heading speech, opening the photo modal, Orca speech for its
initially focused “Yarn photo” title, native Tab to “Close photo”, and Escape
with focus restored to the opener. It also verified the invalid-step alert's
text in AT-SPI. These results predate the current compiled candidate and do
not prove the current integrated flow.

A prior test-only DOM prototype linked and focused the invalid-step error and
captured its exact announcement. The current product now associates the error
with invalid step-title fields and moves focus to the first invalid control
after local validation. The packaged-core Chromium regression exercised that
focus and association successfully, but the current Orca journey stopped
before reaching it. Native spoken validation therefore remains unverified.

The earlier dialog candidate was an isolated Vite build, not a realized Nix
package or deployed source. Its source and test receipts remain historical
records only. Private traces, synthetic fixtures, and browser profiles stay
outside the source tree; this report records no private trace contents.
