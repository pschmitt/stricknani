# Web UI device acceptance plan

## Target and privacy boundary

- The approved test target is an ASUS Zenfone 10 (`ASUS_AI2302`), attached over
  USB. Do not substitute the primary phone, an emulator, or another device.
- Before each device action, confirm the model and USB transport, confirm QEMU is
  absent, and verify the phone is already awake with the display on and
  `KeyguardServiceDelegate` reporting `showing=false` and
  `inputRestricted=false`. Never wake or unlock the phone for this test.
- Use only the disposable Rust fixture and synthetic account, project, and
  media. Keep its credential and state files private. Do not inspect or list
  existing browser tabs, titles, URLs, screenshots, cookies, or profile data.
- Start normal Chrome without flags or settings changes. Create one new
  DevTools-owned blank tab, use that tab only, then close only that tab. Do not
  close the browser, existing tabs, or its existing context.
- Use a unique per-run host under `.localhost` so host-only cookies and origin
  storage cannot collide with other local applications. Do not fall back to
  `127.0.0.1` in the phone browser: cookies are host-scoped across ports.
  If the unique `.localhost` host cannot load the fixture normally, stop and
  report the failure without changing DNS, Chrome settings, or `Host`/`Origin`
  headers.
- A temporary loopback-only SSH tunnel, ADB forward, and ADB reverse may connect
  the test workstation, fixture host, and phone. Refuse occupied ports and use
  no-rebind options. Remove only the rules created by this run; preserve any
  pre-existing ADB mappings.
- Do not use production accounts or data. Do not modify phone settings, enable
  debugging flags, enable TalkBack globally, or access personal browser data.

## Proposed device journey

1. Start the synthetic fixture using the realised Rust server and web package.
   The fixture binds to host loopback only and disables signup and integrations.
2. Connect Playwright to the Chrome DevTools socket through the reviewed local
   bridge. Create a blank target without enumerating existing tabs.
3. Navigate only to the unique `.localhost` fixture origin. Verify sign-in,
   project search, project reader, library navigation, keyboard visibility,
   and an unsaved editor draft retained while the soft keyboard opens and
   closes.
4. Record the browser's actual CSS viewport, `visualViewport`, device pixel
   ratio, safe-area insets, document overflow, touch-target counts, and the
   limited Event Timing samples. Do not override the device viewport or claim
   these samples represent production p75 INP.
5. Sign out through the UI when possible. Clear storage only for the exact
   unique synthetic origin through CDP, then close the owned tab.
6. Stop the fixture and tunnel; remove only this run's ADB forward/reverse
   rules. Keep logs mode `0600` and report only sanitized metrics and status.

## Inventory and run result

- Read-only inventory confirmed one connected USB device matching the approved
  ASUS target, no emulator/QEMU indicators, `Awake`, display `ON`, and keyguard
  flags `showing=false`, `inputRestricted=false`, `secure=true`, `enabled=true`.
- Chrome's DevTools socket was initially absent. Standard Chrome was launched
  without flags or settings changes; the socket then became available. No
  existing tabs or personal page data were read.
- The disposable fixture and temporary loopback bridges were created. The
  runner created a new owned target and attempted to navigate to a unique
  `stricknani-device-<nonce>.localhost` fixture host. Navigation failed at the
  sign-in page with Playwright exception type `Error`; the runner intentionally
  suppressed browser exception text, so the evidence does not distinguish name
  resolution from tunnel/connectivity failure. No credentials were entered and
  no journey metrics were collected. No fallback to `127.0.0.1` was attempted.
- Cleanup completed: the owned target was closed; the runner cleared only the
  unique synthetic origin without reporting a cleanup error; the fixture was
  stopped and its credential/state files and runtime directory were removed;
  the temporary ADB forward/reverse and SSH tunnel were removed. The one
  pre-existing reverse mapping was preserved. Chrome was left open.
- A later ad hoc preflight incorrectly reported zero devices because it
  required a literal tab before the `adb devices -l` status column; the long
  format uses whitespace. Its host-process QEMU check was also the wrong
  signal, and the display parser expected `state=ON` instead of Android's
  `state ON`. Corrected read-only checks found the single approved USB target,
  device QEMU properties absent, the internal display `ON`, `Awake`, and
  keyguard `showing=false` / `inputRestricted=false`. No device disconnect or
  reconnect request occurred.
- A bounded health-only browser probe then used a unique nonce `.localhost`
  origin, with no login or credentials. The initial SSH tunnel direction failed
  authentication; after reversing the tunnel direction, the loopback fixture
  and Chrome DevTools endpoints each returned HTTP 200. Browser navigation
  reached an HTTP error and emitted only
  `net::ERR_HTTP_RESPONSE_CODE_FAILURE`. An ADB-shell `curl` request to
  `/healthz` with the same nonce `Host` header returned HTTP 400; direct fixture
  loopback health and the host-side tunneled health request with the default
  loopback `Host` returned HTTP 200. This confirms the nonce hostname and
  transport reached the fixture; the observed failure is specific to the
  nonce `Host` request, not an unresolved DNS-versus-bridge question.
- Cleanup completed: the owned browser target was closed and the exact nonce
  origin was cleared without a cleanup error; the disposable fixture/state and
  runtime were removed; the owned SSH tunnel and exact ADB forward/reverse
  rules were removed. Other reverse rules were not targeted. Chrome remained
  open. Sanitized run logs and exit receipts remain mode `0600`.
- The health-only runner uses a nonce `.localhost` origin and emits only an
  HTTP status or sanitized `net::ERR_*` code. It passed Python compilation,
  Ruff check/format, MyPy, and mandatory health tests at runner SHA-256
  `64182a71c2d9e40e0aa4e689d293329859f62539c510950bf3e7bd7eec908d15`. The
  browser probe exited 1 on the HTTP error. This is historical; the fixture's
  trusted-host configuration was corrected in published revision `329fe65`.

## Latest device run

Fresh preflight confirmed the approved USB phone was awake, unlocked, and had
its display on; QEMU was absent. The unique `.localhost` fixture origin and
Chrome DevTools bridge each passed their endpoint probes. The single full
journey stopped at the first sign-in tap because the required trusted
`touchstart` and `touchend` events were not observed on the target. The runner
stopped without signing in or running any app workflow assertions. No root cause
was established, and this is not an accepted phone journey.

Cleanup completed: the runner cleared only its nonce origin and closed only its
owned tab, stopped the disposable fixture and tunnel, and removed only its own
ADB forward/reverse mappings. Unrelated mappings and browser tabs were left
alone; Chrome remained open. No further physical-device attempt is included in
this checkpoint. Physical-device acceptance remains open.
