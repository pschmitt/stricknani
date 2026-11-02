# Screen-reader verification

**Status: partial evidence; complete screen-reader acceptance is still open.**

On 2026-11-02, I ran Orca 50.2 with Firefox 1555 and the frozen combined UI
against a disposable Rust fixture. Orca, Speech Dispatcher 0.12.1 using its dummy
speech module, Firefox, Xvfb, and D-Bus all ran in private temporary state.
The synthetic fixture was authenticated before Orca started, so the password
was not exposed to speech output. No audio device or user desktop was used.

An exploratory run captured an Orca `SPEECH OUTPUT` entry containing the
synthetic “Projects” heading. That confirms Orca produced speech output for app
content in the fixture. Its key event came through Playwright's browser
protocol, however, so it does not prove that Orca's native heading-navigation
command produced that utterance.

The run12 debug trace contained 111 total `SPEECH OUTPUT` markers; an earlier
count of 13 was only a partial scan, not the log total. None of the expected
labels were confirmed by the runner's action-offset checks. Run12 reached the
Yarns navigation attempt but did not reach the expected route after native
Return. The project headings, navigation label, photo-dialog title/focus,
focus restoration, project-field label, and validation alert were not verified.
The Orca launch output also reported a missing Python `gi` module, but included
no traceback frame identifying the importing code path.

For run13, the Orca subprocess received the seven Python 3.14 dependency
`site-packages` paths in its own `PYTHONPATH`; the worker and Firefox
environment were not changed. A private preflight imported all six required
modules (`gi`, `cairo`, `xdg`, `psutil`, `dasbus`, and `setproctitle`) with the
exact Nix Python 3.14.8 interpreter. The Orca output no longer reported missing
`gi`, and the debug trace again contained 111 total speech markers. However,
the runner could not reach the Yarns navigation link with its bounded sequence
of 80 native Tab presses. Native Return and the route transition were not
attempted in this run. No expected post-action speech label was confirmed, and
later dialog, field, and alert checks did not run. This does not establish an
application accessibility defect; the integrated screen-reader journey remains
unproven.

The run13 runner source SHA-256 is
`6ff39320ee493b7b1e90af8924a42362adb509e9cc1146b5a45b58a1bad9d275`. Its
private runtime-module report SHA-256 is
`d49779c9ecee2c092134acc4195ebba1f25cb771535179d0eb946a1fe2c37ccb`; the
boolean journey report SHA-256 is
`17aacb865ac49f210b85839c8ac8e2c3e33ed46dde1cdc55d13bbd99872b12e5`. Run13
exited 1 at native keyboard traversal to the Yarns link. The run12 private
receipt and boolean report remain under
`/home/pschmitt/build/stricknani-native-a11y-17d8e231/screenreader-luna-20261102/run12/`;
the run13 reports and traces remain in the adjacent private run directory.
Both runs used only a synthetic fixture and isolated browser, Xvfb, D-Bus, and
speech-dispatcher state. Fixture cleanup removed disposable server state and
credentials; private trace files were retained for diagnosis and were not
copied into the repository.

For run13, Ruff check, Ruff format check, MyPy, and Python compilation passed.
`tests/test_health.py` passed all four tests. Runtime dependency preflight and
speech output are not substitutes for successful native navigation and
expected-label checks. The runner is not a passing CI acceptance test until
those journey checks complete successfully.

Run16 used a bounded, per-key Tab traversal rather than sending a rapid batch.
Before Orca started, a native XTest keyboard marker was entered into and
cleared from the search field; an XTest click was also verified on the
Projects heading. Both probes passed. The run then sent 24 native Tab keys,
and Orca emitted speech output after 6 of those steps, but none announced the
expected Yarns link and the browser DOM focus never reached that link. The run
stopped at the Yarns navigation stage, so native Return and the route change
were not attempted. This confirms delivery of native input and some speech
output, but does not complete a screen-reader journey or identify a product
defect.

Run16's runner source SHA-256 is
`2ea90385f85df1ce0b18b715ecfc5fc4201b7a046ab23cdbccb5a70f58e1ee76`.
Its private run receipt is under
`/home/pschmitt/build/stricknani-native-a11y-17d8e231/screenreader-luna-20261102/run16/`;
the private boolean report records the native input probes and per-step
results. A setup-only run before this did not start a browser; it exposed a
missing source-root `PYTHONPATH` in the isolated worker environment. The
worker now receives only its owned source root, while Orca continues to use
only its approved Python 3.14 dependency paths.

For the run16 runner, Python compilation, Ruff check, Ruff format check, and
the four health tests passed. MyPy with `--follow-imports=silent` passed for
the runner itself. A normal targeted MyPy invocation still reports typing
errors in imported fixture/UI modules; those are outside this runner change
and have not been classified against the full-source baseline here. The
screen-reader acceptance remains open pending successful expected-label
speech and native route navigation.

Run17 exercised the explicit `--interaction-mode pointer` path. Orca started
with the same isolated fixture and its Python 3.14 module preflight passed.
The native pointer event on the search field was trusted, dispatched, and
targeted correctly, but the expected “Search your projects” announcement was
not found after the click. The run stopped at that check, before testing native
typing, route navigation, reader, editor, dialog, or validation speech. Its
separate pointer-check record does not mark any native-Tab requirement as
passed; the earlier native-Tab result remains unchanged.

The pointer-mode runner source SHA-256 is
`a2725bfe4c6ba1184ea5d737316b79896f463927fb2c4dfe5ae6eb83d9cd90cd`. Its
private receipt is under
`/home/pschmitt/build/stricknani-native-a11y-17d8e231/screenreader-luna-20261102/run17/`.
The completed checks still do not establish the full screen-reader journey.

Run18 added an immediate DOM-focus check after the trusted search click, before
waiting for speech. The pointer event was trusted, dispatched, and targeted;
the search field became `document.activeElement`. Native test typing while
Orca was alive reached the field and was cleared. Orca still did not emit the
expected search-field label, so the run stopped before route, reader, editor,
dialog, restoration, or alert checks. The browser's ordinary pointer and
keyboard delivery therefore work at the search field, but the expected
screen-reader announcement remains unverified.

The run18 log review found seven `ConnectionError` entries in Orca's debug
trace with no Python traceback frames, two generic D-Bus-associated error
lines in Orca stdout, and 18, 8, and 1 error-marked lines in the Speech
Dispatcher, dummy module, and espeak fallback logs respectively. Those
messages did not provide a typed exception or source frame that identifies a
backend failure. All six Orca Python modules imported successfully. In a
separate owned blank Firefox
profile, `accessibility.force_disabled` had no user override; the packaged
Firefox preference default is `0`. The worker uses an explicit environment
allowlist with `MOZ_ENABLE_ACCESSIBILITY=1` and `GTK_A11Y=atspi`, so inherited
accessibility-disable flags are not passed. No preference or global
environment override was applied.

Run18 used an Xvfb launch with D-Bus but no window manager. It did not record
an EWMH active-window value or Firefox AT-SPI `STATE_ACTIVE`; those results
were not measured in that run. Run19 later verified the EWMH active window
matched the owned Firefox window; its optional AT-SPI `FRAME.STATE_ACTIVE`
probe was unavailable. The run18 private receipt is under
`/home/pschmitt/build/stricknani-native-a11y-17d8e231/screenreader-luna-20261102/run18/`.
The run18 runner SHA-256 is
`bd4d7c550f324912c30a696db115a438720c1f1515710d2f77ca065f0ac39620`.

Run19 added one private Openbox window manager directly to the test's Xvfb and
D-Bus session. It used a minimal mode-0600 configuration in the private run
directory, disabled session-manager integration, and did not use an Openbox
session launcher or the user's desktop configuration. Before the semantic
pointer journey, the EWMH active-window check matched the owned Firefox window.
The optional AT-SPI `FRAME.STATE_ACTIVE` probe was unavailable, so no AT-SPI
active-state result is claimed.

The native search click was trusted, targeted the search field, and made it
the DOM-focused element. Native keyboard input reached the field and was
cleared. Orca still did not announce the search-field label, and the run
stopped at that first speech check. No route, reader, editor, photo-dialog,
focus-restoration, or validation-alert speech checks ran. This remains an
incomplete screen-reader journey; the active-window result does not establish
that Openbox fixed Orca's speech path.

Run19 used the committed `6a30d04944ed` application source with the native
screen-reader helper and runner overlays. Its runner SHA-256 is
`13699b4273911d13d0bdc148ead7cc6bf545442e538377872a5a7b6014837e85`. The
private receipt and boolean diagnostics are under
`/home/pschmitt/build/stricknani-native-a11y-17d8e231/screenreader-luna-20261102/run19/`;
the captured launcher output is a separate mode-0600 file in that private run
parent. The runner passed Ruff check, Ruff format check, and Python syntax
validation on the isolated source copy. The browser run exited 1 at the search
speech check after 32 seconds.
