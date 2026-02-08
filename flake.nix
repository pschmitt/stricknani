{
  description = "Stricknani - A self-hosted web app for managing knitting projects";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    pre-commit-hooks.url = "github:cachix/pre-commit-hooks.nix";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
      pre-commit-hooks,
    }:
    let
      eachSystem = flake-utils.lib.eachDefaultSystem (
        system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
          python = pkgs.python313;

          fastapi-csrf-protect = python.pkgs.callPackage ./nix/fastapi-csrf-protect.nix { };

          stricknani = pkgs.callPackage ./nix/package.nix {
            python3 = python;
            inherit fastapi-csrf-protect;
          };

          pre-commit-check = pre-commit-hooks.lib.${system}.run {
            src = ./.;
            hooks = {
              # Python
              ruff.enable = true;
              ruff-format.enable = true;
              mypy = {
                enable = true;
                settings.binPath = "${pkgs.uv}/bin/uv run mypy";
              };

              # Nix
              statix.enable = true;

              # JS/CSS
              prettier = {
                enable = true;
                settings.binPath = "${pkgs.nodePackages.prettier}/bin/prettier";
              };
            };
          };
        in
        {
          packages = {
            default = stricknani;
            inherit stricknani;

            stricknani-docker = pkgs.dockerTools.buildLayeredImage {
              name = "ghcr.io/pschmitt/stricknani";
              tag = "latest";
              config = {
                Cmd = [ "${stricknani}/bin/stricknani" ];
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
            inherit (pre-commit-check) shellHook;
            buildInputs = with pkgs; [
              python
              uv
              just
              ruff
              mypy
              statix
              poppler-utils
              tesseract
              nodePackages.prettier
            ];

            shellHook = ''
              ${pre-commit-check.shellHook}
              echo "Stricknani development environment"
              echo "Run 'just setup' to initialize the project"
              echo "Run 'just run' to start the development server"
            '';
          };

          checks = {
            build = stricknani;
            inherit pre-commit-check;
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
