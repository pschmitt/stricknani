# The Rust server core, built with crane: the dependency crates are their own derivation
# (`cargoArtifacts`), so a change to Stricknani's own sources compiles only this crate and every
# build, check and deploy reuses the cached dependencies (nixbot, niks3). The package itself does
# not run the checks; fmt, Clippy and the test suite are separate flake checks on top of the same
# artifacts.
{
  lib,
  craneLib,
}:

let
  src = lib.fileset.toSource {
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

  commonArgs = {
    inherit src;
    pname = "stricknani-server-core";
    # Single source of the release version.
    version = (lib.importTOML ../Cargo.toml).package.version;
    strictDeps = true;
  };

  # Built from Cargo.toml and Cargo.lock only: unchanged unless a dependency changes.
  cargoArtifacts = craneLib.buildDepsOnly commonArgs;

  package = craneLib.buildPackage (
    commonArgs
    // {
      inherit cargoArtifacts;
      # The checks below run the test suite; the package only builds.
      doCheck = false;
      meta = {
        description = "The Rust server core for Stricknani";
        license = lib.licenses.gpl3Only;
        maintainers = [ "Philipp Schmitt" ];
        mainProgram = "stricknani-server";
      };
    }
  );
in
{
  inherit package cargoArtifacts;

  checks = {
    rust-fmt = craneLib.cargoFmt { inherit src; };
    rust-clippy = craneLib.cargoClippy (
      commonArgs
      // {
        inherit cargoArtifacts;
        cargoClippyExtraArgs = "--all-targets --all-features -- -D warnings";
      }
    );
    rust-test = craneLib.cargoTest (
      commonArgs
      // {
        inherit cargoArtifacts;
        cargoTestExtraArgs = "--all-targets";
      }
    );
  };
}
