{
  buildNpmPackage,
  lib,
  nodejs_26,
  # Short commit (plus -dirty) shown on the About page; the Nix source has no .git.
  gitRevision ? "",
}:

buildNpmPackage rec {
  pname = "stricknani-web";
  # Single source of the release version.
  version = (lib.importTOML ../Cargo.toml).package.version;
  src = ../web;
  nodejs = nodejs_26;
  npmDepsHash = "sha256-CiOJgg9ALTr1k3XHY+dsqwbjlgO/HEx1Cd5vMNGFtVw=";
  npmBuildScript = "build";
  env = {
    STRICKNANI_GIT_REVISION = gitRevision;
    # web/ alone has no Cargo.toml; vite.config.ts reads the version from here.
    STRICKNANI_VERSION = version;
  };
  doCheck = true;
  checkPhase = ''
    runHook preCheck
    npm run format:check
    npm run check
    # The e2e/lib tests need the whole repository (sources, catalogs), which the
    # sandbox does not have; `just web-test` runs them in CI.
    npx vitest run src
    runHook postCheck
  '';

  installPhase = ''
    runHook preInstall
    install -d "$out"
    cp -a build/. "$out/"
    runHook postInstall
  '';

  meta = {
    description = "Static browser application for Stricknani";
    license = lib.licenses.gpl3Only;
  };
}
