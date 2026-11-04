{
  lib,
  rustPlatform,
  rustfmt,
  clippy,
}:

let
  rustSource = lib.fileset.toSource {
    root = ../.;
    fileset = lib.fileset.unions [
      ../Cargo.toml
      ../Cargo.lock
      ../src
      ../tests
      ../migrations
      ../templates
      ../examples
      ../locales
      ../web/static/manifest.webmanifest
      ../web/static/offline.html
      ../web/static/offline.css
      ../web/static/offline-theme.js
      ../web/static/service-worker.js
    ];
  };
in
rustPlatform.buildRustPackage {
  pname = "stricknani-server-core";
  version = "1.1.1";
  src = rustSource;
  cargoLock.lockFile = "${rustSource}/Cargo.lock";

  nativeCheckInputs = [
    rustfmt
    clippy
  ];
  preCheck = ''
    cargo fmt --check
    cargo clippy --offline --all-targets --all-features -- -D warnings
  '';

  meta = {
    description = "The Rust server core for Stricknani";
    license = lib.licenses.gpl3Only;
    maintainers = [ "Philipp Schmitt" ];
    mainProgram = "stricknani-server";
  };
}
