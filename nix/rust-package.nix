{
  lib,
  stdenvNoCC,
  makeWrapper,
  cacert,
  tesseract,
  corePackage,
  webPackage,
}:

let
  legacyStaticSource = lib.fileset.toSource {
    root = ../stricknani/static;
    fileset = ../stricknani/static;
  };

  ocrEngine = tesseract.override {
    enableLanguages = [
      "deu"
      "eng"
    ];
  };
in
stdenvNoCC.mkDerivation {
  pname = "stricknani-server";
  version = "0.1.0";
  dontUnpack = true;
  nativeBuildInputs = [ makeWrapper ];

  installPhase = ''
    runHook preInstall

    install -d "$out/bin"
    install -d "$out/share/stricknani/static"
    install -d "$out/share/stricknani/web"

    install -m 0555 ${corePackage}/bin/stricknani-server \
      "$out/bin/.stricknani-server-wrapped"
    cp -a --no-preserve=mode ${legacyStaticSource}/. \
      "$out/share/stricknani/static/"
    cp -a --no-preserve=mode ${webPackage}/. \
      "$out/share/stricknani/web/"

    # The Rust pages use their own forms, OCR script, and service worker.
    rm -rf "$out/share/stricknani/static/js" \
      "$out/share/stricknani/static/vendor/htmx" \
      "$out/share/stricknani/static/vendor-tiptap"

    makeWrapper "$out/bin/.stricknani-server-wrapped" \
      "$out/bin/stricknani-server" \
      --set STATIC_ROOT "$out/share/stricknani/static" \
      --set WEB_ROOT "$out/share/stricknani/web" \
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
