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
      ../rust
      ../web/static/manifest.webmanifest
      ../web/static/offline.html
      ../web/static/offline.css
      ../web/static/service-worker.js
    ];
  };

  legacyStaticSource = lib.fileset.toSource {
    root = ../stricknani/static;
    fileset = ../stricknani/static;
  };
in
rustPlatform.buildRustPackage {
  pname = "stricknani-server-core";
  version = "1.0.0";
  src = rustSource;
  sourceRoot = "source/rust";
  cargoLock.lockFile = "${rustSource}/rust/Cargo.lock";
  STATIC_ROOT = legacyStaticSource;

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
