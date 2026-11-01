# Web UI foundation

The browser UI is a static SvelteKit application mounted at `/app`. Axum serves
the generated files and the same-origin API; Node is only needed while building
and checking the frontend.

```sh
npm ci
npm run dev
npm run check
npm run build
```

The static adapter writes to `web/build`, with `200.html` as the client-router
fallback. The Rust host must serve the app only for known `/app` routes and
preserve normal errors for APIs, media, health checks, and unknown assets. Vite
does not proxy API requests; development needs a same-origin frontend/API host.

The foundation currently implements session bootstrap, sign-in, sign-out,
session-expiry handling, role-gated navigation, and honest preview pages. Project,
yarn, import, account, gauge, and admin workflows are not implemented yet.

The PO-generated public catalogue is `web/static/messages.json`. The PO files
remain the translation source of truth; the frontend loads the generated JSON
from `/app/messages.json` and falls back to each English source string. The
catalogue file is maintained by the repository i18n generator.

After `vite build`, `scripts/write-csp-manifest.mjs` scans the generated HTML
and writes `web/build/csp.json`. It contains the exact SHA-256 hashes of inline
script and style blocks for the Rust response CSP. No `unsafe-inline` or
`unsafe-eval` exception is required.
