{
  description = "Project Studio FastAPI environment";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
      in {
        devShells.default = pkgs.mkShell {
          packages = [
            pkgs.uv
            pkgs.just
            pkgs.openssl
            pkgs.pkg-config
            pkgs.sqlite
          ];
          shellHook = ''
            export UV_PYTHON_INSTALL_DIR="$PWD/.uv-python"
            if [ ! -d "$UV_PYTHON_INSTALL_DIR" ];
            then
              uv python install 3.14 >/dev/null 2>&1 || true
            fi
            export UV_PYTHON="$UV_PYTHON_INSTALL_DIR/cpython-3.14.0/bin/python3"
          '';
        };
      }
    );
}
