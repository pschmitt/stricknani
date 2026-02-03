{
  lib,
  buildPythonPackage,
  fetchurl,
  hatchling,
  fastapi,
  pydantic,
  pydantic-settings,
  itsdangerous,
}:

buildPythonPackage rec {
  pname = "fastapi-csrf-protect";
  version = "1.0.7";
  pyproject = true;

  src = fetchurl {
    url = "https://files.pythonhosted.org/packages/f6/1a/fedbcb4aba24ccc8abfb5d30e08112073c6a9f20b8d88adbdd3051ceedac/fastapi_csrf_protect-1.0.7.tar.gz";
    sha256 = "sha256-iIsVsjJiWq5bmX+8+B70VjOnaU8DEqBU8e7G0TKylfs=";
  };

  nativeBuildInputs = [
    hatchling
  ];

  propagatedBuildInputs = [
    fastapi
    pydantic
    pydantic-settings
    itsdangerous
  ];

  # Tests require network or are not included in PyPI dist
  doCheck = false;

  meta = with lib; {
    description = "Stateless CSRF (Cross-Site Request Forgery) Protection for FastAPI";
    homepage = "https://github.com/a-m-s/fastapi-csrf-protect";
    license = licenses.mit;
    maintainers = [ ];
  };
}
