# Documentation Map

Use this index to find the right document quickly.

- `../AGENTS.md`: operational development rules for coding agents
- `../TODO.md`: prioritized execution backlog
- `../README.md`: what Stricknani is, quick install, and where the rest lives

## Run and maintain

- `install.md`: container, Nix package, and development setup
- `rust-nixos.md`: the NixOS module
- `maintaining.md`: releases, deploys, translations, vendoring, and migrations
- `oidc-server-configuration.md`, `oidc-authelia-settings.md`: OpenID Connect
  sign-in on the server and in Authelia

## APIs and contracts

- `web-v2-contract.md`: browser and native API v2
- [project-timer-api.md](project-timer-api.md): shared project stopwatch contract
- `web-v2-audit-contract.md`: audit resources and history
- `oidc-v2-contract.md`: OpenID Connect API v2 flows
- `android-v2-parity.md`: what the Android app calls against the server routes

## Server internals

- `rust-import-design.md`, `rust-document-worker.md`, `rust-ocr.md`,
  `rust-ai.md`, `rust-wayback.md`: imports and their helpers
- `rust-audit.md`: audit events
- `rust-cli-selection.md`: server commands and the legacy CLI mapping
- `rust-security-review.md`, `auth-security-followups.md`: security review and
  open follow-ups

## Web app and Android checks

- `rust-pwa.md`: PWA and offline cache behaviour
- `web-ui-design.md`: shared UI decisions
- `web-ui-browser-check.md`, `web-ui-device-acceptance.md`,
  `web-ui-native-a11y.md`, `web-ui-screenreader.md`, `web-ui-contrast.md`,
  `web-ui-performance.md`: browser, device, accessibility, and performance
  checks
- `web-ui-review-followups.md`: open UI review items
- `rust-replay-ci.md`: Android replay CI against the Rust server

## Product

- `spec-and-implementation.md`: product specification; its implementation
  snapshot describes the legacy Python app (tag `v0.1.0`)
