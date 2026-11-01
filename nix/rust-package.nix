{
  lib,
  rustPlatform,
  makeWrapper,
  rustfmt,
  clippy,
  cacert,
}:

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
    cp -a ${../stricknani/static}/. "$out/share/stricknani/static/"
    wrapProgram "$out/bin/stricknani-server" \
      --set STATIC_ROOT "$out/share/stricknani/static" \
      --set-default SSL_CERT_FILE "${cacert}/etc/ssl/certs/ca-bundle.crt"
  '';

  meta = {
    description = "A self-hosted web app for managing knitting projects";
    license = lib.licenses.gpl3Only;
    maintainers = [ "Philipp Schmitt" ];
    mainProgram = "stricknani-server";
  };
}
