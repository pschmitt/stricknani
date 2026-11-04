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
          # Python development tooling (i18n generators, browser harnesses and
          # their tests). The playwright wheel and the browsers come from the
          # same nixpkgs, so their versions always match.
          pythonTooling = pkgs.python313.withPackages (
            ps: with ps; [
              babel
              mypy
              playwright
              pytest
              shapely
            ]
          );
          pythonToolingPackages = [
            pythonTooling
            pkgs.ruff
            pkgs.just
            pkgs.resvg
          ];
          playwrightEnv = {
            # Tells scripts/just-shell (the justfile's shell) it is already in a dev shell.
            STRICKNANI_DEVSHELL = "1";
            PLAYWRIGHT_BROWSERS_PATH = "${pkgs.playwright-driver.browsers}";
            PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS = "true";
          };

          # Commit of this flake for the About page; empty when the revision is unknown.
          gitRevision =
            if self ? rev then
              builtins.substring 0 8 self.rev
            else if self ? dirtyRev then
              "${builtins.substring 0 8 self.dirtyRev}-dirty"
            else
              "";

          stricknaniWeb = pkgs.callPackage ./nix/web-package.nix { inherit gitRevision; };
          stricknaniRustCore = pkgs.callPackage ./nix/rust-core-package.nix { };
          stricknaniRust = pkgs.callPackage ./nix/rust-package.nix {
            corePackage = stricknaniRustCore;
            webPackage = stricknaniWeb;
            inherit gitRevision;
          };

          rustContainerConfig = {
            Cmd = [ "${stricknaniRust}/bin/stricknani-server" ];
            User = "65532:65532";
            WorkingDir = "/var/lib/stricknani";
            ExposedPorts = {
              "7674/tcp" = { };
            };
            Env = [
              "BIND_HOST=0.0.0.0"
              "BIND_PORT=7674"
              "RUST_DATABASE_URL=sqlite:/var/lib/stricknani/stricknani.sqlite3"
              "RUST_MEDIA_ROOT=/var/lib/stricknani/media"
              "TRUSTED_HOSTS=localhost,127.0.0.1"
              "SESSION_COOKIE_SECURE=true"
              "RUST_SIGNUP_ENABLED=false"
              "RUST_EXTERNAL_IMPORTS_ENABLED=false"
              "RUST_AI_ENABLED=false"
              "RUST_OCR_ENABLED=false"
              "RUST_WAYBACK_ENABLED=false"
            ];
            Healthcheck = {
              Test = [
                "CMD"
                "${stricknaniRust}/bin/stricknani-server"
                "health-check"
              ];
              Interval = 30000000000;
              Timeout = 5000000000;
              StartPeriod = 10000000000;
              Retries = 3;
            };
          };

          pre-commit-check = pre-commit-hooks.lib.${system}.run {
            src = ./.;
            hooks = {
              # Python tooling (scripts/, ci/)
              ruff.enable = true;
              ruff-format.enable = true;

              # Nix
              statix.enable = true;

              # Kotlin (android/): the same nixpkgs ktfmt CI's lint job runs
              # (android-lint.yaml, ktfmt-runner: nix), so local and CI formatting agree.
              ktfmt = {
                enable = true;
                name = "ktfmt";
                entry = "${pkgs.ktfmt}/bin/ktfmt --kotlinlang-style";
                files = "^android/.*\\.kts?$";
              };
            };
          };
        in
        {
          packages = {
            default = stricknaniRust;
            stricknani = stricknaniRust;
            stricknani-web = stricknaniWeb;

            stricknani-docker = pkgs.dockerTools.buildLayeredImage {
              name = "ghcr.io/pschmitt/stricknani";
              tag = "latest";
              contents = [ stricknaniRust ];
              fakeRootCommands = ''
                mkdir -p ./tmp ./var/lib/stricknani
                chmod 1777 ./tmp
                chown 65532:65532 ./var/lib/stricknani
              '';
              config = rustContainerConfig;
            };
          };

          apps = {
            default = {
              type = "app";
              program = "${stricknaniRust}/bin/stricknani-server";
              meta.description = "Run the Rust Stricknani server";
            };
            stricknani = self.apps.${system}.default;
          };

          # These shells keep Android lint and the Rust/web/tooling development
          # environment separate.
          devShells = {
            # `nix develop .#ktfmt` supplies the binary used by Android lint.
            ktfmt = pkgs.mkShell { packages = [ pkgs.ktfmt ]; };

            # Python tooling only: `nix develop .#tooling -c just lint-ruff`.
            tooling = pkgs.mkShell ({ packages = pythonToolingPackages; } // playwrightEnv);

            rust = pkgs.mkShell (
              {
                packages =
                  pythonToolingPackages
                  ++ (with pkgs; [
                    cargo
                    cargo-audit
                    clippy
                    rust-analyzer
                    rustc
                    rustfmt
                    sqlite
                    pkg-config
                    statix
                    nixfmt
                    ktfmt
                    nodejs
                    poppler-utils
                    tesseract
                  ]);

                shellHook = ''
                  ${pre-commit-check.shellHook}
                '';
              }
              // playwrightEnv
            );

            default = self.devShells.${system}.rust;
          };

          checks = {
            build = stricknaniRust;
            rust-build = stricknaniRust;
            inherit pre-commit-check;
          };
        }
      );
    in
    eachSystem
    // {
      # x86_64-linux package builds and checks run on the self-hosted nixbot.
      # aarch64-linux builds natively on GitHub's arm runners (the nix-aarch64
      # job of .github/workflows/nix.yaml); the build nodes could only emulate it.
      hydraJobs = nixpkgs.lib.genAttrs [ "x86_64-linux" ] (system: {
        packages = self.packages.${system} or { };
        checks = self.checks.${system} or { };
      });

      nixosModules.default = import ./nix/module.nix;
      nixosModules.stricknani = self.nixosModules.default;
    };
}
