# Screen-reader verification

**Status: strict native screen-reader acceptance passed for the isolated 72b1 candidate.**

## Accepted candidate

The run used the canonical public-root-pinned 72b1 package: web root
`/nix/store/gyqjfr40aiszvyg9l8lxp7i8wq2qlgjc-stricknani-web-0.1.0`, server
package `/nix/store/n65q658kcrqh0qbqkfnsds8k7m007pa8-stricknani-server-0.1.0`,
and raw server SHA-256
`460dd562505f51518d7c65fdabd199e7d27210a55574f7bc83bb428b667fcbcd`.
The embedded and standalone web roots matched across 107 files.

The synthetic browser ran Firefox and Orca with Speech Dispatcher’s dummy
output, Xvfb, and private D-Bus state. It used no user desktop, audio device,
or live service. The strict native journey exited 0. All 13 recorded report
checks passed, including native input, navigation and heading speech, photo
dialog title and focus, Escape focus restoration, project editor controls, and
invalid-step focus and error speech. The runner also requires native Tab to
the dialog’s visible Close photo button, verifies both DOM focus and Orca
speech for that button, then verifies Escape restores focus and speech to the
opener.

The Yarns link’s AT-SPI object had role `link`, accessible name “Yarns”, active
state, and the expected Firefox frame. The native-key, focus and speech
timestamps show that the runner's unchanged 1.25s action window captured the
utterance; the additional 10s bounded fallback did not need to run.

The private runner SHA-256 was
`c68f8ad452d3ab0b848ea4cd8e099e7536acfe356a3efaa7ead683bd733b49d6`. Its exit
receipt is
`/home/pschmitt/build/stricknani-validation-focus-72b1faa-native-20261102/receipts/native-orca/run-delay03.exit`.
The safe report is outside the source tree at
`/tmp/sr72b1-orca-20261102/delay03/screenreader-report.json`.

## Capture setup

The observation-only Orca shim preserves `_print_text` and flushes the same
private debug handle after each write. It does not inject speech or alter
accessibility events. Its SHA-256 is
`5938078d3639ae2a7d3d9733aff4adf0f9ed5998504bae05ad992b6222a7f1ac`.

An initial diagnostic runner placed outside the source tree derived an
incorrect worker `PYTHONPATH` from its file location. The corrected private
copy was placed under `source/ci/stricknani`, where the runner’s existing path
logic resolves the source root; the shim was copied unchanged to the private
build-root location that the runner derives. Exact executable/library checks,
runner import, and isolated Xvfb/D-Bus startup passed before the accepted run.
The corrected run captured Yarns speech during the native action window.

An earlier static dialog prototype and its validation-focus prototype are
historical diagnostics, not acceptance results for this candidate. Private
traces, synthetic fixtures, and browser profiles remain outside the source
tree.
