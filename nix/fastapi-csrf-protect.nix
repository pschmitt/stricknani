{
  lib,
  buildPythonPackage,
  fetchurl,
  setuptools,
  poetry-core,
  fastapi,
  pydantic,
  itsdangerous,
}:

buildPythonPackage rec {
  pname = "fastapi-csrf-protect";
  version = "0.3.3";
  pyproject = true;

  src = fetchurl {
    url = "https://files.pythonhosted.org/packages/f3/74/611bf8ed4e2a43b95c75fe34d16caadecd0959a52e87499de430adebdca6/fastapi_csrf_protect-0.3.3.tar.gz";
    sha256 = "sha256-yUYojMoisNjGXHJSAw8+kx7i+cJKYhu7S8VsKccpqTg=";
  };

  nativeBuildInputs = [
    setuptools
    poetry-core
  ];

  propagatedBuildInputs = [
    fastapi
    pydantic
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
