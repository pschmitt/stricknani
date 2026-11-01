# Rust candidate security review

## Dependency audit (2026-11-01)

`cargo audit` exited 1: it reported RSA timing advisory
[RUSTSEC-2023-0071](https://rustsec.org/advisories/RUSTSEC-2023-0071)
for `rsa 0.9.10`, and an unmaintained notice for `basic-toml 0.1.10`
(RUSTSEC-2026-0374). This is not a clean audit result.

`cargo tree --invert rsa` has no active dependency path. RSA is present in
the lockfile through optional SQLx drivers; this application enables SQLite
only and does not compile or execute RSA. Decision: retain SQLite-only
features; do not enable MySQL or RSA functionality without reassessing the
advisory. Review by 2026-12-01 or before changing database features.

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
`/static/` assets use a one-hour public cache with stale revalidation.

## Remaining release work

Browser sessions/CSRF, full browser security headers and sanitization,
URL-import SSRF/redirect policy, copied-data migration verification, and
staging isolation must pass their own acceptance checks before the candidate is
a production replacement.
