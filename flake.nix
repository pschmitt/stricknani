{
  description = "Stricknani - A self-hosted web app for managing knitting projects";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        python = pkgs.python313;
      in
      {
        packages = {
          default = self.packages.${system}.stricknani;

          stricknani = python.pkgs.buildPythonApplication {
            pname = "stricknani";
            version = "0.1.0";
            format = "pyproject";

            src = ./.;

            nativeBuildInputs = with python.pkgs; [
              hatchling
            ];

            propagatedBuildInputs = with python.pkgs; [
              fastapi
              uvicorn
              jinja2
              sqlalchemy
              alembic
              python-multipart
              python-jose
              bcrypt
              python-dotenv
              pillow
              markdown
              bleach
              aiosqlite
            ];

            meta = {
              description = "A self-hosted web app for managing knitting projects";
              license = pkgs.lib.licenses.gpl3Only;
              maintainers = [ "Philipp Schmitt" ];
            };
          };

          stricknani-docker = pkgs.dockerTools.buildLayeredImage {
            name = "ghcr.io/pschmitt/stricknani";
            tag = "latest";
            config = {
              Cmd = [ "${self.packages.${system}.stricknani}/bin/stricknani" ];
              ExposedPorts = {
                "7674/tcp" = {};
              };
              Env = [
                "BIND_HOST=0.0.0.0"
              ];
            };
          };
        };

        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            python
            uv
            just
            ruff
            mypy
          ] ++ (with python.pkgs; [
            fastapi
            uvicorn
            jinja2
            sqlalchemy
            alembic
            python-multipart
            python-jose
            bcrypt
            python-dotenv
            pillow
            markdown
            bleach
            aiosqlite
            pytest
            pytest-asyncio
            httpx
          ]);

          shellHook = ''
            echo "Stricknani development environment"
            echo "Run 'just setup' to initialize the project"
            echo "Run 'just run' to start the development server"
          '';
        };

        checks = {
          build = self.packages.${system}.stricknani;
        };
      }
    );
}
