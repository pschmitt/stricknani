{
  buildNpmPackage,
  lib,
  nodejs_22,
  # Short commit (plus -dirty) shown on the About page; the Nix source has no .git.
  gitRevision ? "",
}:

buildNpmPackage {
  pname = "stricknani-web";
  version = "1.1.0";
  src = ../web;
  nodejs = nodejs_22;
  npmDepsHash = "sha256-j6CLHm3yZV361nw4bBCC2AtmvtI6XKOJ7wQmTXMWft0=";
  npmBuildScript = "build";
  env.STRICKNANI_GIT_REVISION = gitRevision;
  doCheck = true;
  checkPhase = ''
    runHook preCheck
    npm run format:check
    npm run check
    npm test
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
