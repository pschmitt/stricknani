# Web UI browser acceptance

The browser acceptance runner starts an isolated Rust fixture with a disposable
SQLite database and media directory, serves the built static UI through the
real Rust router, and exercises login, session restoration, logout, route
handling, and responsive layouts in Chromium. It checks strict CSP, expected
same-origin assets, mobile navigation, 44px navigation controls, reduced
motion, and the absence of unexpected browser errors. Fixture credentials,
cookies, and response bodies are never printed or captured.

The 2026-11-01 integrated run passed against the static build and Rust server.
It generated sanitized signed-out mobile, signed-in mobile, and post-logout
screenshots in a private temporary output directory; screenshots are not
checked into the repository.

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
