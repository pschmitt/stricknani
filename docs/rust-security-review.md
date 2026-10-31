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

- Explicit Rust storage settings and application ownership marker; legacy
  database/media paths are rejected when inherited alongside Rust settings.
- Argon2id for new passwords; successful legacy bcrypt verification upgrades
  the hash conditionally. Owned password strings are zeroized after use.
- Opaque random PATs, SHA-256 token storage, expiry/revocation/active-user
  checks, bounded password concurrency and atomic login rate limiting.
- Per-owner and per-parent resource/media checks, strict image byte/decode
  limits, canonical storage containment, safe attachment disposition, and
  durable media deletion queue.
- Canonical UTC microsecond timestamps and owner-bound pagination cursors.

## Remaining release work

Browser sessions/CSRF, trusted proxy/host configuration, full browser security
headers and sanitization, URL-import SSRF/redirect policy, copied-data
migration verification, and staging isolation must pass their own acceptance
checks before the candidate is a production replacement.
