{
  description = "Stricknani - A self-hosted web app for managing knitting projects";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    pre-commit-hooks.url = "github:cachix/pre-commit-hooks.nix";
    # Rust builds with the dependency crates as their own, cached derivation
    # (nix/rust-core-package.nix).
    crane.url = "github:ipetkov/crane";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
      pre-commit-hooks,
      crane,
    }:
    let
      eachSystem = flake-utils.lib.eachDefaultSystem (
        system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
          # Node/TypeScript development tooling (i18n generators and the Playwright
          # browser acceptance suite under web/). The browsers come from the same
          # nixpkgs as the pinned @playwright/test version in web/package.json
          # (keep them equal), so their versions always match.
          toolingPackages = [
            pkgs.nodejs_26
            pkgs.just
            pkgs.resvg
          ];
          playwrightEnv = {
            # Tells scripts/just-shell (the justfile's shell) it is already in a dev shell.
            STRICKNANI_DEVSHELL = "1";
            PLAYWRIGHT_BROWSERS_PATH = "${pkgs.playwright-driver.browsers}";
            PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS = "true";
          }
          // pkgs.lib.optionalAttrs pkgs.stdenv.hostPlatform.isLinux {
            # Playwright's WebKit needs an EGL display even when headless, and a
            # Nix shell has no /run/opengl-driver on a build worker or CI runner
            # ("Could not create EGL display"); the e2e harness hands these Mesa
            # software-rendering paths to the WebKit process only.
            STRICKNANI_WEBKIT_EGL_VENDOR = "${pkgs.mesa}/share/glvnd/egl_vendor.d/50_mesa.json";
            STRICKNANI_WEBKIT_LIBRARY_PATH = "${pkgs.mesa}/lib:${pkgs.libglvnd}/lib";
          };

          # @playwright/test in web/package.json must equal nixpkgs' playwright-driver,
          # whose browsers the dev shells provide. Renovate does not bump it; the
          # nix-hash-fixup workflow aligns it when flake.lock moves nixpkgs.
          playwrightPinned =
            (builtins.fromJSON (builtins.readFile ./web/package.json)).devDependencies."@playwright/test";
          playwright-pin =
            if playwrightPinned == pkgs.playwright-driver.version then
              pkgs.runCommand "playwright-pin" { } "touch $out"
            else
              throw "web/package.json pins @playwright/test ${playwrightPinned}, nixpkgs has playwright-driver ${pkgs.playwright-driver.version}; set them equal";

          # Commit of this flake for the About page; empty when the revision is unknown.
          gitRevision =
            if self ? rev then
              builtins.substring 0 8 self.rev
            else if self ? dirtyRev then
              "${builtins.substring 0 8 self.dirtyRev}-dirty"
            else
              "";

          stricknaniWeb = pkgs.callPackage ./nix/web-package.nix { inherit gitRevision; };
          stricknaniRustCore = pkgs.callPackage ./nix/rust-core-package.nix {
            craneLib = crane.mkLib pkgs;
          };
          stricknaniRust = pkgs.callPackage ./nix/rust-package.nix {
            corePackage = stricknaniRustCore.package;
            webPackage = stricknaniWeb;
            inherit gitRevision;
          };

          # Fully static (musl) server binary for the GitHub release assets. Built natively on
          # each architecture (x86_64 here, aarch64 on GitHub's arm runners), not cross-compiled.
          stricknaniRustStatic =
            (pkgs.pkgsStatic.callPackage ./nix/rust-core-package.nix {
              craneLib = crane.mkLib pkgs.pkgsStatic;
            }).package;

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
              "STRICKNANI_DATABASE_URL=sqlite:/var/lib/stricknani/stricknani.sqlite3"
              "STRICKNANI_MEDIA_ROOT=/var/lib/stricknani/media"
              "TRUSTED_HOSTS=localhost,127.0.0.1"
              "SESSION_COOKIE_SECURE=true"
              "SIGNUP_ENABLED=false"
              "EXTERNAL_IMPORTS_ENABLED=false"
              "AI_ENABLED=false"
              "OCR_ENABLED=false"
              "WAYBACK_ENABLED=false"
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
              # Nix
              statix.enable = true;

              # Kotlin (android/): the same nixpkgs ktfmt CI's lint job runs
              # (the android-lint job of checks.yaml, ktfmt-runner: nix), so local and CI formatting agree.
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
            # Static musl `stricknani-server` without the Nix wrapper (release assets, see
            # docs/maintaining.md). Set WEB_ROOT to the stricknani-web output to serve the app.
            stricknani-server-static = stricknaniRustStatic;

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

            # Node tooling only: `nix develop .#tooling -c just i18n-check`.
            tooling = pkgs.mkShell ({ packages = toolingPackages; } // playwrightEnv);

            rust = pkgs.mkShell (
              {
                packages =
                  toolingPackages
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
            inherit pre-commit-check playwright-pin;
          }
          # fmt, Clippy and the test suite, on the cached dependency artifacts.
          // stricknaniRustCore.checks;
        }
      );
    in
    eachSystem
    // {
      # x86_64-linux package builds and checks run on the self-hosted nixbot.
      # aarch64-linux builds natively on GitHub's arm runners (the nix-aarch64
      # job of .github/workflows/build.yaml); the build nodes could only emulate it.
      hydraJobs = nixpkgs.lib.genAttrs [ "x86_64-linux" ] (system: {
        packages = self.packages.${system} or { };
        checks = self.checks.${system} or { };
      });

      nixosModules.default = import ./nix/module.nix;
      nixosModules.stricknani = self.nixosModules.default;
    };
}
