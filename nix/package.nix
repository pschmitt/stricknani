{
  lib,
  python3,
  fastapi-csrf-protect,
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
    fastapi
    fastapi-csrf-protect
    httpx
    httptools
    jinja2
    markdown
    openai
    pillow
    python-dotenv
    python-jose
    python-multipart
    rich
    scikit-image
    sentry-sdk
    sqlalchemy
    trafilatura
    uvicorn
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
  ];

  propagatedBuildInputs = pythonDeps;

  meta = {
    description = "A self-hosted web app for managing knitting projects";
    license = lib.licenses.gpl3Only;
    maintainers = [ "Philipp Schmitt" ];
    mainProgram = "stricknani";
  };
}
