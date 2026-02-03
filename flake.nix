{
  description = "Stricknani - A self-hosted web app for managing knitting projects";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    let
      eachSystem = flake-utils.lib.eachDefaultSystem (
        system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
          python = pkgs.python313;

          pythonDeps = with python.pkgs; [
            aiosqlite
            alembic
            babel
            bcrypt
            beautifulsoup4
            nh3
            cryptography
            fastapi
            httpx
            httptools
            jinja2
            markdown
            nh3
            openai
            pillow
            python-dotenv
            python-jose
            python-multipart
            rich
            sentry-sdk
            sqlalchemy
            trafilatura
            uvicorn
            weasyprint
            uvloop
            watchfiles
            weasyprint
            websockets
          ];
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

              propagatedBuildInputs = pythonDeps;

              meta = {
                description = "A self-hosted web app for managing knitting projects";
                license = pkgs.lib.licenses.gpl3Only;
                maintainers = [ "Philipp Schmitt" ];
                mainProgram = "stricknani";
              };
            };

            stricknani-docker = pkgs.dockerTools.buildLayeredImage {
              name = "ghcr.io/pschmitt/stricknani";
              tag = "latest";
              config = {
                Cmd = [ "${self.packages.${system}.stricknani}/bin/stricknani" ];
                ExposedPorts = {
                  "7674/tcp" = { };
                };
                Env = [
                  "BIND_HOST=0.0.0.0"
                ];
              };
            };
          };

          devShells.default = pkgs.mkShell {
            buildInputs =
              with pkgs;
              [
                python
                uv
                just
                ruff
                mypy
              ]
              ++ pythonDeps;

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
    in
    eachSystem
    // {
      nixosModules.default = import ./nix/module.nix;
      nixosModules.stricknani = self.nixosModules.default;
    };
}