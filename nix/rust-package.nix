{
  lib,
  stdenvNoCC,
  makeWrapper,
  cacert,
  tesseract,
  corePackage,
  webPackage,
  # Short commit (plus -dirty) for the Sentry release name, set at runtime by the
  # wrapper so the Rust core build stays cached across commits.
  gitRevision ? "",
}:

let
  ocrEngine = tesseract.override {
    enableLanguages = [
      "deu"
      "eng"
    ];
  };
in
stdenvNoCC.mkDerivation {
  pname = "stricknani-server";
  version = "1.2.1";
  dontUnpack = true;
  nativeBuildInputs = [ makeWrapper ];

  installPhase = ''
    runHook preInstall

    install -d "$out/bin"
    install -d "$out/share/stricknani/web"

    install -m 0555 ${corePackage}/bin/stricknani-server \
      "$out/bin/.stricknani-server-wrapped"
    cp -a --no-preserve=mode ${webPackage}/. \
      "$out/share/stricknani/web/"

    makeWrapper "$out/bin/.stricknani-server-wrapped" \
      "$out/bin/stricknani-server" \
      --set WEB_ROOT "$out/share/stricknani/web" \
      ${
        lib.optionalString (
          gitRevision != ""
        ) "--set STRICKNANI_GIT_REVISION ${lib.escapeShellArg gitRevision}"
      } \
      --set-default SSL_CERT_FILE "${cacert}/etc/ssl/certs/ca-bundle.crt" \
      --prefix PATH : "${lib.makeBinPath [ ocrEngine ]}"

    runHook postInstall
  '';

  passthru.corePackage = corePackage;

  meta = {
    description = "A self-hosted web app for managing knitting projects";
    license = lib.licenses.gpl3Only;
    maintainers = [ "Philipp Schmitt" ];
    mainProgram = "stricknani-server";
  };
}
