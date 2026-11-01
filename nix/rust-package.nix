{
  lib,
  rustPlatform,
  makeWrapper,
  rustfmt,
  clippy,
  cacert,
  tesseract,
  webPackage,
}:

let
  ocrEngine = tesseract.override {
    enableLanguages = [
      "deu"
      "eng"
    ];
  };
in
rustPlatform.buildRustPackage {
  pname = "stricknani-server";
  version = "0.1.0";
  src = ../rust;
  cargoLock.lockFile = ../rust/Cargo.lock;
  STATIC_ROOT = ../stricknani/static;

  nativeBuildInputs = [ makeWrapper ];
  nativeCheckInputs = [
    rustfmt
    clippy
  ];
  preCheck = ''
    cargo fmt --check
    cargo clippy --offline --all-targets --all-features -- -D warnings
  '';

  postInstall = ''
    install -d "$out/share/stricknani/static"
    cp -a --no-preserve=mode ${../stricknani/static}/. "$out/share/stricknani/static/"
    install -d "$out/share/stricknani/web"
    cp -a --no-preserve=mode ${webPackage}/. "$out/share/stricknani/web/"
    # The Rust pages use their own forms, OCR script, and service worker.
    rm -rf "$out/share/stricknani/static/js" \
      "$out/share/stricknani/static/vendor/htmx" \
      "$out/share/stricknani/static/vendor-tiptap"
    wrapProgram "$out/bin/stricknani-server" \
      --set STATIC_ROOT "$out/share/stricknani/static" \
      --set WEB_ROOT "$out/share/stricknani/web" \
      --set-default SSL_CERT_FILE "${cacert}/etc/ssl/certs/ca-bundle.crt" \
      --prefix PATH : "${lib.makeBinPath [ ocrEngine ]}"
  '';

  meta = {
    description = "A self-hosted web app for managing knitting projects";
    license = lib.licenses.gpl3Only;
    maintainers = [ "Philipp Schmitt" ];
    mainProgram = "stricknani-server";
  };
}
