{
  lib,
  makeWrapper,
  poppler-utils,
  tesseract,
  python3,
  fastapi-csrf-protect,
  tailwindcss_4,
}:

let
  python = python3;
  pythonDeps = with python.pkgs; [
    aiosqlite
    alembic
    babel
    bcrypt
    beautifulsoup4
    nh3
    cryptography
    curl-cffi
    fastapi
    fastapi-csrf-protect
    httpx
    httptools
    jinja2
    markdown
    openai
    pillow
    pymupdf
    pypdf
    python-dotenv
    python-jose
    python-multipart
    rich
    scikit-image
    sentry-sdk
    sqlalchemy
    trafilatura
    uvicorn
    waybackpy
    weasyprint
    uvloop
    watchfiles
    websockets
  ];
in
python.pkgs.buildPythonApplication {
  pname = "stricknani";
  version = "0.1.0";
  format = "pyproject";

  src = lib.cleanSource ./..;

  nativeBuildInputs = with python.pkgs; [
    hatchling
    babel
  ] ++ [
    makeWrapper
    tailwindcss_4
  ];

  propagatedBuildInputs = pythonDeps;

  # Prebuild the static Tailwind CSS bundle (scans templates + static/js for
  # utility classes) so the package ships without any runtime/browser
  # Tailwind JIT. Mirrors `just build-css` / the Dockerfile build stage.
  #
  # Also compile gettext catalogs (.mo) at build time so they are shipped in
  # the store output and never lazily compiled into the read-only store at
  # request time.
  preBuild = ''
    tailwindcss \
      -i stricknani/static/css/tailwind.input.css \
      -o stricknani/static/css/tailwind.css \
      --minify
    ${python.pkgs.babel}/bin/pybabel compile -d stricknani/locales
  '';

  postFixup = ''
    wrapProgram "$out/bin/stricknani" \
      --prefix PATH : ${lib.makeBinPath [ poppler-utils tesseract ]}
    wrapProgram "$out/bin/stricknani-cli" \
      --prefix PATH : ${lib.makeBinPath [ poppler-utils tesseract ]}
  '';

  meta = {
    description = "A self-hosted web app for managing knitting projects";
    license = lib.licenses.gpl3Only;
    maintainers = [ "Philipp Schmitt" ];
    mainProgram = "stricknani";
  };
}
