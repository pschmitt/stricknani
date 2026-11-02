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
  no-rebind options where supported. Otherwise verify the exact port is free
  before creating a mapping and verify its result. Remove only the rules created
  by this run; preserve any pre-existing ADB mappings.
- Do not use production accounts or data. Do not modify phone settings, enable
  debugging flags, enable TalkBack globally, or access personal browser data.

## Proposed device journey

1. Start the synthetic fixture using the realised Rust server and web package.
   The fixture binds to host loopback only and disables signup and integrations.
2. Use the minimal owned-target CDP client through the reviewed local bridge:
   read version metadata, create/activate one new blank target, then attach only
   to that target's page WebSocket. Do not use a raw Playwright browser connection,
   target discovery, automatic attachment or personal-context operations.
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
  explicit existing-tab or personal-page reads were requested. The raw
  Playwright connector's internal discovery was not validated; that historical
  path is not privacy-isolated acceptance.
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

## Current 72b1 device evidence

The approved USB target passed a fresh read-only readiness check: model and
product matched, QEMU was absent, the device was interactive with its internal
display on, and keyguard was clear. A new synthetic fixture used the canonical
`72b1faa` server/web package and a unique nonce `.localhost` origin.

The minimal client created and activated one owned target, then used only its
page WebSocket. A trusted touch/click reached the email control through the
expected composed path, was not default-prevented, and focused the field after
269ms. The owned page had focus and the IME was visible. No credentials or
native key were sent in this probe. The parent reviewed its protocol method
inventory: no target listing/automatic attachment or global cookie operation.

The touch runner SHA-256 is
`ee72c8f9c3ac4a233ecac21a7f78e1a2565dba7848b560cc05bec3c26868401f`.
On the device-fixture worker, the exit receipt is
`/home/pschmitt/build/stricknani-final-72b1-browser-luna-20261102/private/phone-a8a0ac75646c/touch-run-02/probe.exit`
and contains `0`; its sanitized log SHA-256 is
`0d0f6ba70229e39a9f307ceeee3afb2c6883a94995df95858f9a9cfc8112fcf5`.

Before the full journey, a later preflight could no longer positively establish
display-on/interactivity. Input stopped. The owned target/origin, fixture,
tunnel, forward `tcp:19220` and reverse `tcp:18763` were cleaned up; the
pre-existing reverse `tcp:18762` was preserved because current ownership was
unverified. Full sign-in/editor/media/navigation/sign-out acceptance remains
open. The complete driver is being validated on an isolated headless fixture
before another already-awake phone window is requested.

## Historical 52221ad device diagnostics

The focused login probe used the exact packaged 52221ad server and web package
with a new synthetic fixture. After the IME reduced the visual viewport to
435.3 CSS px at offset 69.5, one native Android Back restored it to 750.2 CSS
px at offset 0. The `/app/login` route and both filled fields remained intact.
The recomputed sign-in target was fully visible and hit-tested. Trusted
`touchstart`/`touchend` reached a span inside the button; the composed path and
event coordinates matched. The login request returned HTTP 200 and the
Projects heading appeared. The owned tab was closed and only the nonce origin
was cleared.

That result is diagnostic rather than accepted device evidence: the harness
connected Playwright to the forwarded, unfiltered Chrome DevTools browser
socket. It did not explicitly enumerate or read existing tabs, but Playwright's
internal target discovery was not verified. This raw browser-socket path is
not acceptable for further runs. `PUT /json/new?about:blank` failed on this
browser. A subsequent private connector successfully created and activated one
owned target with `Target.createTarget`, then attached directly to that target's
page WebSocket. It used no target discovery, listing or automatic attachment.
That owned-target setup and cleanup passed; a complete app journey did not.

The single full-run attempt did not reach its native keyboard handshake. It
timed out during sign-in before a keyboard-ready marker was written; no native
key was sent and no sign-in assertion passed. A setup-only probe reproduced
the precondition: programmatic field filling left the visual viewport at its
750.2 px baseline. A later setup trace dispatched a trusted touch to the
visible password field and confirmed its target, composed path, and coordinates,
but the expected visual viewport resize did not occur before timeout. No root
cause is established for that behavior.

The owned-target follow-up also failed to focus the login field or show its
keyboard through the tested touch path. A headless fixture diagnostic passed,
which does not establish physical-device behavior. A later readiness check
found no ready approved transport; the current checkpoint did not repeat it
because its approved identity source was unavailable. No wake, unlock or
substitute-device action was taken.

Owned fixture processes, tabs, nonce storage, local forwarding and SSH tunnels
were cleaned up where available. Verification/removal of the exact owned ADB
reverse mapping `tcp:18762` remains deferred after the device became unavailable.
Recheck only that mapping when the approved device is already awake and unlocked.
The earlier raw Playwright connection's internal profile access remains
unverified; it must not be described as privacy-isolated acceptance. The full
physical-device journey, including search, reader navigation, editor keyboard
behavior, upload and sign-out, remains unverified and acceptance stays open.
