{
  description = "Stricknani Android development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    android-nixpkgs = {
      url = "github:tadfisher/android-nixpkgs";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    git-hooks = {
      url = "github:cachix/git-hooks.nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    android-app-ci = {
      url = "github:pschmitt/android-app-ci";
      flake = false;
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      android-nixpkgs,
      git-hooks,
      android-app-ci,
    }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config.allowUnfree = true;
      };

      # SNA-63: android-nixpkgs's "cmdline-tools-latest" currently resolves to cmdline-tools 23.0,
      # which bundles a new native "android" CLI binary replacing the old sdkmanager script/jar.
      # That binary is fatally incompatible with a Nix build in two independent ways, discovered
      # by bisecting cmdline-tools versions directly against this android-nixpkgs revision:
      #   1. Its ELF interpreter is left at the upstream FHS path (/lib64/ld-linux-x86-64.so.2) -
      #      every android-nixpkgs package sets `dontPatchELF = true` unconditionally
      #      (pkgs/android/generic.nix), harmless while every SDK tool was a script/jar, but fatal
      #      for this one native binary inside a Nix build sandbox (no /lib64 there). This is
      #      exactly this ticket's original symptom: sdk.nix's own `android-sdk-env` composition
      #      build runs `sdkmanager --list --verbose` while assembling the SDK, which shells out to
      #      this unpatched binary and fails with "cannot execute: .android-wrapped: required file
      #      not found".
      #   2. Even patched to run, this binary unconditionally tries to self-download a bundled
      #      "Android CLI" payload from dl.google.com into `$HOME/.android/bin` on first
      #      invocation (confirmed live: `Downloading Android CLI... Error: Failed to download
      #      from https://dl.google.com/... Temporary failure in name resolution`) - a network
      #      fetch that can never succeed inside Nix's sandboxed, offline build environment. This
      #      is a hard blocker, not a permissions nit to patch around.
      #  cmdline-tools 22.0 (one version older, still present in this same android-nixpkgs
      #  revision's channel data) predates this native-CLI switch - its `android-sdk-env` build
      #  was confirmed to succeed standalone with zero modification (`nix build` against a probe
      #  expression selecting only `cmdline-tools-22-0` + `platform-tools`). Pin "latest" to that
      #  known-good version here instead of waiting on an upstream android-nixpkgs fix (the
      #  self-download behavior above means no ELF patch alone can ever make 23.0 buildable
      #  offline) - overriding only `.path` (not the rest of its metadata) so it still installs at
      #  the `cmdline-tools/latest` layout devshells.nix's shellHook PATH hardcodes.
      androidNixpkgsPatched = android-nixpkgs // {
        sdk = builtins.mapAttrs (
          _system: sdkForSystem:
          (
            pkgsFun:
            sdkForSystem (
              sdkPkgs:
              pkgsFun (
                sdkPkgs
                // {
                  cmdline-tools-latest = sdkPkgs.cmdline-tools-22-0 // {
                    inherit (sdkPkgs.cmdline-tools-latest) path;
                  };
                }
              )
            )
          )
        ) android-nixpkgs.sdk;
      };

      androidEnv = import "${android-app-ci}/nix/devshells.nix" {
        inherit pkgs system;
        android-nixpkgs = androidNixpkgsPatched;
        appName = "Stricknani";
        buildToolsVersion = "37.0.0";
        platformVersion = "37-0";
        gitHooksLib = git-hooks.lib;
        # No physical test devices or local AVD-based screenshot capture set up yet (SNA-12).
        screenshotsSystemImage = null;
        quickStart = ''
          echo "  just build debug               # Build a debug APK on rofl-13/rofl-14"
        '';
      };
    in
    {
      devShells.${system} = androidEnv.devShells;
      checks.${system} = androidEnv.checks;
    };
}
