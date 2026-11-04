# Rust candidate security review

## Dependency audit (2026-11-01)

`cargo audit` exited 1: it reported RSA timing advisory
[RUSTSEC-2023-0071](https://rustsec.org/advisories/RUSTSEC-2023-0071)
for `rsa 0.9.10`, and an unmaintained notice for `basic-toml 0.1.10`
(RUSTSEC-2026-0374). This is not a clean audit result.

At the original SQLite-only checkpoint, `cargo tree --invert rsa` had no
active dependency path: RSA appeared only through optional SQLx drivers.
The OIDC candidate changes that assessment. `openidconnect 4.0.1` uses
`rsa 0.9.10` at runtime to verify RS256/PS256 ID-token signatures. The direct
RSA dependency is test-only and signs mock tokens using ephemeral keys on a
loopback mock issuer; the production server has no private-key signing or
decryption path in this integration.

The [RustSec advisory](https://rustsec.org/advisories/RUSTSEC-2023-0071.html)
still lists no patched release. Its key-recovery exposure concerns observable
private-key operations; the reviewed server path performs verification with
public keys. Decision: retain this verification-only dependency path for the
OIDC candidate, review by **2026-12-01**, and reassess immediately before any
private-key operation or provider-library change. This is a scoped source-path
assessment, not a clean audit result or a global suppression. A fresh audit of
the final combined lockfile is still required before release.

`cargo tree --invert basic-toml` leads through Askama's procedural macros.
It parses repository-owned template configuration during compilation rather
than network input at runtime. Decision: retain Askama for this candidate,
watch the upstream parser replacement, and review by 2026-12-01. No global
advisory suppression or new license policy was added.

## Verified boundaries

The expanded 398-crate lockfile was rechecked with cargo-audit 0.22.2 on
2026-11-01. It reports the same two findings above, with no additional advisory.
The Rust development shell now includes cargo-audit. Reqwest uses Rustls with
the platform verifier; the Nix wrapper supplies `SSL_CERT_FILE` from `cacert`
so the non-root candidate has a CA bundle without an OpenSSL dependency.

- Explicit Rust storage settings and application ownership marker; legacy
  database/media paths are rejected when inherited alongside Rust settings.
- Argon2id for new passwords; successful legacy bcrypt verification upgrades
  the hash conditionally. Owned password strings are zeroized after use.
- New or changed passwords require at least 12 Unicode characters and remain
  capped at 1,024 UTF-8 bytes. The existing trimmed, case-insensitive common
  password check is unchanged; login verification does not apply new-password
  policy.
- Opaque random PATs, SHA-256 token storage, expiry/revocation/active-user
  checks, bounded password concurrency and atomic login rate limiting.
- Per-owner and per-parent resource/media checks, strict image byte/decode
  limits, canonical storage containment, safe attachment disposition, and
  durable media deletion queue.
- Canonical UTC microsecond timestamps and owner-bound pagination cursors.

## Web security policy

The public `serve` listener accepts exactly one `Host` authority from the
`TRUSTED_HOSTS` allowlist. Host names compare case-insensitively and exactly;
suffixes do not match. Entries may be written as `name` or `name:port`, and
IPv6 literals must use brackets. An entry without a port accepts that exact
host on any port. Loopback binds default to `localhost`, `127.0.0.1`, and
`[::1]`; non-loopback binds require an explicit allowlist and
`SESSION_COOKIE_SECURE=true`.

The server does not trust `X-Forwarded-Host`, `X-Forwarded-Proto`, or forwarded
client addresses. A reverse proxy must preserve a validated public `Host` and
connect to the listener directly. Trusted-proxy support needs its own explicit
proxy address allowlist before forwarded headers can affect security decisions.

Responses set a same-origin CSP, `X-Content-Type-Options: nosniff`, frame and
referrer restrictions, and a restrictive Permissions Policy. Dynamic, API,
authentication, health, and media responses use `private, no-store`; successful
`/app/_app/` assets use a one-hour public cache with stale revalidation.

## Remaining release work

The browser session/CSRF protections, browser security headers and sanitization,
URL-import policy, copied-snapshot verification, and isolated storage are
implemented and covered by the current Rust tests or migration checks. Remaining
work is tracked in [`TODO.md`](../TODO.md). Android replay CI and the final container
checks now pass; broader Android UI coverage is being connected to Rust. The
final-snapshot, backup, and rollback procedure is documented but unexecuted.
Production cutover remains separately gated by the user's explicit approval. The two
dependency-audit findings above remain under their stated review dates.
