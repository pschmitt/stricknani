{
  buildNpmPackage,
  lib,
  nodejs_26,
  # Short commit (plus -dirty) shown on the About page; the Nix source has no .git.
  gitRevision ? "",
}:

buildNpmPackage {
  pname = "stricknani-web";
  version = "1.1.0";
  src = ../web;
  nodejs = nodejs_26;
  npmDepsHash = "sha256-cpJhfT49kpNvbqrQx2N8TxQW2tDDfPicx3Y9QpquUtw=";
  npmBuildScript = "build";
  env.STRICKNANI_GIT_REVISION = gitRevision;
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
