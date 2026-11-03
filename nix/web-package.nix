{
  buildNpmPackage,
  lib,
  nodejs_22,
}:

buildNpmPackage {
  pname = "stricknani-web";
  version = "1.0.0";
  src = ../web;
  nodejs = nodejs_22;
  npmDepsHash = "sha256-cOY8DTD2ZMik8lfQqfdOEgBD8cBlZeaJd0QHS8/rT+U=";
  npmBuildScript = "build";
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
