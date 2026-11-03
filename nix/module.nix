{
  config,
  lib,
  pkgs,
  ...
}:
let
  cfg = config.services.stricknani;
  user = "stricknani";
  group = user;
  oidcEnabledEnvironmentFile = pkgs.writeText "stricknani-oidc-enabled.env" ''
    RUST_OIDC_ENABLED=${lib.boolToString cfg.oidc.enable}
  '';
  oidcEnvironment = lib.optionalAttrs cfg.oidc.enable (
    lib.optionalAttrs (cfg.oidc.issuer != null) {
      RUST_OIDC_ISSUER = cfg.oidc.issuer;
    }
    // lib.optionalAttrs (cfg.oidc.clientId != null) {
      RUST_OIDC_CLIENT_ID = cfg.oidc.clientId;
    }
    // lib.optionalAttrs (cfg.oidc.clientSecretFile != null) {
      RUST_OIDC_CLIENT_SECRET_FILE = cfg.oidc.clientSecretFile;
    }
    // lib.optionalAttrs (cfg.oidc.callbackUri != null) {
      RUST_OIDC_CALLBACK_URI = cfg.oidc.callbackUri;
    }
    // lib.optionalAttrs (cfg.oidc.nativeRedirectUris != null) {
      RUST_OIDC_NATIVE_REDIRECT_URIS = lib.concatStringsSep "," cfg.oidc.nativeRedirectUris;
    }
  );
  loopbackBind = lib.hasPrefix "127." cfg.bindHost || cfg.bindHost == "::1";
  trustedHosts = lib.concatStringsSep "," (
    lib.optional (cfg.hostName != null) cfg.hostName
    ++ cfg.serverAliases
    ++ [
      "127.0.0.1"
      "localhost"
    ]
  );
  backupScript = pkgs.writeShellScript "stricknani-backup" ''
    set -euo pipefail
    umask 0077

    BACKUP_DIR='${cfg.backup.dir}'
    MEDIA_DIR='${cfg.mediaRoot}'
    DB_URL='${cfg.databaseUrl}'
    RETENTION='${toString cfg.backup.retention}'
    TIMESTAMP="$(date -u +%Y%m%d-%H%M%S)"
    ARCHIVE_TMP="$BACKUP_DIR/stricknani-$TIMESTAMP.tar.gz.tmp"
    ARCHIVE_FILE="$BACKUP_DIR/stricknani-$TIMESTAMP.tar.gz"

    mkdir -p "$BACKUP_DIR"

    TMP_DIR="$(mktemp -d "$BACKUP_DIR/.stricknani-backup-$TIMESTAMP.XXXXXX")"

    cleanup() {
      rm -rf -- "$TMP_DIR"
      rm -f -- "$ARCHIVE_TMP"
    }

    trap cleanup EXIT

    if [[ "$DB_URL" == sqlite:/* ]]
    then
      DB_PATH="''${DB_URL#sqlite:}"
      while [[ "$DB_PATH" == //* ]]
      do
        DB_PATH="''${DB_PATH#/}"
      done
      if [[ "$DB_URL" == *\?* || "$DB_URL" == *#* || "$DB_PATH" != /* ]]
      then
        echo "stricknani-backup: unsupported SQLite URL" >&2
        exit 2
      fi
    else
      echo "stricknani-backup: requires file-backed SQLite" >&2
      exit 2
    fi

    if [[ ! -f "$DB_PATH" ]]
    then
      echo "stricknani-backup: sqlite database not found at '$DB_PATH'" >&2
      exit 2
    fi

    DB_SNAPSHOT="database.sqlite3"
    sqlite3 "$DB_PATH" ".backup $TMP_DIR/$DB_SNAPSHOT"

    if [[ -d "$MEDIA_DIR" ]]
    then
      tar -czf "$ARCHIVE_TMP" -C "$TMP_DIR" "$DB_SNAPSHOT" \
        -C "$(dirname "$MEDIA_DIR")" "$(basename "$MEDIA_DIR")"
    else
      echo "stricknani-backup: media directory missing at '$MEDIA_DIR'" >&2
      tar -czf "$ARCHIVE_TMP" -C "$TMP_DIR" "$DB_SNAPSHOT"
    fi

    mv "$ARCHIVE_TMP" "$ARCHIVE_FILE"

    trap - EXIT

    rm -rf -- "$TMP_DIR"

    mapfile -t BACKUPS < <(ls -1dt "$BACKUP_DIR"/stricknani-* 2>/dev/null || true)

    if (( ''${#BACKUPS[@]} > RETENTION ))
    then
      for OLD_BACKUP in "''${BACKUPS[@]:RETENTION}"
      do
        rm -f -- "$OLD_BACKUP"
      done
    fi
  '';

  stricknaniCliWrapper = pkgs.writeShellScriptBin "stricknani-cli" ''
    exec sudo -u "${user}" -- env \
      -u DATABASE_URL -u MEDIA_ROOT \
      RUST_DATABASE_URL="${cfg.databaseUrl}" \
      RUST_MEDIA_ROOT="${cfg.mediaRoot}" \
      ${pkgs.runtimeShell} -c '
        cd "${cfg.dataDir}";
        exec "${cfg.package}/bin/stricknani-server" "$@" \
      ' -- "$@"
  '';
in
{
  options.services.stricknani = {
    enable = lib.mkEnableOption "Stricknani knitting project manager";

    backend = lib.mkOption {
      type = lib.types.enum [ "rust" ];
      default = "rust";
      description = "Implementation used by the Stricknani service. Rust is the only implementation; the option remains so existing configurations keep evaluating.";
    };

    package = lib.mkOption {
      type = lib.types.package;
      default = pkgs.callPackage ./rust-package.nix {
        corePackage = pkgs.callPackage ./rust-core-package.nix { };
        webPackage = pkgs.callPackage ./web-package.nix { };
      };
      description = "The Stricknani package to use.";
    };

    dataDir = lib.mkOption {
      type = lib.types.path;
      default = "/var/lib/stricknani-rust";
      description = "The directory where stricknani stores its data.";
    };

    port = lib.mkOption {
      type = lib.types.port;
      default = 7674;
      description = "The port stricknani should listen on.";
    };

    bindHost = lib.mkOption {
      type = lib.types.str;
      default = "127.0.0.1";
      description = "The host stricknani should bind to.";
    };

    hostName = lib.mkOption {
      type = lib.types.nullOr lib.types.str;
      default = null;
      description = "The primary hostname for stricknani.";
    };

    serverAliases = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ ];
      description = "Additional hostnames for stricknani.";
    };

    secretKeyFile = lib.mkOption {
      type = lib.types.nullOr lib.types.path;
      default = null;
      description = "Path to a file containing environment variables (e.g. SECRET_KEY, OPENAI_API_KEY).";
    };

    gravatar.enable = lib.mkOption {
      type = lib.types.bool;
      default = true;
      description = "Let the server fetch Gravatar images (SHA-256 of the account email) for users without an uploaded avatar. Clients never contact Gravatar. Sets RUST_GRAVATAR_ENABLED; users can still opt out individually.";
    };

    oidc = {
      enable = lib.mkEnableOption "OIDC sign-in for the Rust backend";
      providerId = lib.mkOption {
        type = lib.types.enum [ "authelia" ];
        default = "authelia";
        description = "Configured OIDC provider identifier.";
      };
      issuer = lib.mkOption {
        type = lib.types.nullOr lib.types.str;
        default = null;
        description = "OIDC issuer URL. Prefer environmentFile for deployment-specific values.";
      };
      clientId = lib.mkOption {
        type = lib.types.nullOr lib.types.str;
        default = null;
        description = "OIDC client ID. Prefer environmentFile for deployment-specific values.";
      };
      clientSecretFile = lib.mkOption {
        type = lib.types.nullOr lib.types.str;
        default = null;
        description = "Absolute runtime path to the OIDC client secret file; never the secret value.";
      };
      callbackUri = lib.mkOption {
        type = lib.types.nullOr lib.types.str;
        default = null;
        description = "Exact HTTPS OIDC callback URI, including authority and port.";
      };
      nativeRedirectUris = lib.mkOption {
        type = lib.types.nullOr (lib.types.listOf lib.types.str);
        default = null;
        description = "Exact native OIDC redirect URI allowlist; null uses the Rust backend default.";
      };
      environmentFile = lib.mkOption {
        type = lib.types.nullOr lib.types.str;
        default = null;
        description = "Protected runtime environment file containing OIDC provider settings and client secret file path.";
      };
    };

    databaseUrl = lib.mkOption {
      type = lib.types.str;
      default = "sqlite:${cfg.dataDir}/rust.sqlite3";
      description = "The database connection string.";
    };

    mediaRoot = lib.mkOption {
      type = lib.types.path;
      default = "${cfg.dataDir}/media";
      description = "Directory used for uploaded media.";
    };

    sessionCookieSecure = lib.mkOption {
      type = lib.types.bool;
      default = cfg.nginx.enable && cfg.nginx.forceSSL;
      description = "Set Secure on session cookies for HTTPS deployments.";
    };

    backup = {
      enable = lib.mkOption {
        type = lib.types.bool;
        default = true;
        description = "Enable scheduled Stricknani database backups.";
      };

      dir = lib.mkOption {
        type = lib.types.str;
        default = "${cfg.dataDir}/backups";
        description = "Directory where backup archives are stored.";
      };

      schedule = lib.mkOption {
        type = lib.types.str;
        default = "daily";
        description = "Backup schedule (systemd OnCalendar expression).";
      };

      retention = lib.mkOption {
        type = lib.types.ints.positive;
        default = 7;
        description = "How many recent backups to keep.";
      };
    };

    nginx = {
      enable = lib.mkEnableOption "Nginx virtual host for Stricknani";
      forceSSL = lib.mkOption {
        type = lib.types.bool;
        default = true;
        description = "Whether to force SSL for the virtual host.";
      };
      enableACME = lib.mkOption {
        type = lib.types.bool;
        default = true;
        description = "Whether to enable ACME for the virtual host.";
      };
    };

    extraConfig = lib.mkOption {
      type = lib.types.attrsOf lib.types.str;
      default = { };
      description = "Extra configuration options as environment variables.";
    };
  };

  config = lib.mkIf cfg.enable {
    assertions = [
      {
        assertion =
          lib.hasPrefix "sqlite:/" cfg.databaseUrl
          && !(lib.hasInfix "?" cfg.databaseUrl)
          && !(lib.hasInfix "#" cfg.databaseUrl);
        message = "services.stricknani requires a file-backed SQLite databaseUrl without query or fragment.";
      }
      {
        assertion = lib.hasPrefix "/" (toString cfg.mediaRoot);
        message = "services.stricknani requires an absolute mediaRoot.";
      }
      {
        assertion = toString cfg.dataDir != "/var/lib/stricknani";
        message = "services.stricknani requires a dataDir separate from the legacy Python data directory (/var/lib/stricknani), which holds the pre-cutover snapshot source.";
      }
      {
        assertion = loopbackBind || cfg.sessionCookieSecure;
        message = "services.stricknani requires secure session cookies for non-loopback listeners.";
      }
      {
        assertion = loopbackBind || cfg.hostName != null || cfg.serverAliases != [ ];
        message = "services.stricknani requires a hostname or server alias for non-loopback listeners.";
      }
      {
        assertion = !(cfg.extraConfig ? RUST_DATABASE_URL) && !(cfg.extraConfig ? RUST_MEDIA_ROOT);
        message = "Set Rust database and media paths with databaseUrl and mediaRoot, not extraConfig.";
      }
      {
        assertion =
          !cfg.oidc.enable
          || cfg.oidc.environmentFile != null
          || (
            cfg.oidc.issuer != null
            && cfg.oidc.clientId != null
            && cfg.oidc.clientSecretFile != null
            && cfg.oidc.callbackUri != null
          );
        message = "services.stricknani.oidc requires oidc.environmentFile or all provider values.";
      }
      {
        assertion = !cfg.oidc.enable || cfg.sessionCookieSecure;
        message = "services.stricknani.oidc requires secure session cookies.";
      }
      {
        assertion = builtins.all (name: !(lib.hasPrefix "RUST_OIDC_" name)) (
          builtins.attrNames cfg.extraConfig
        );
        message = "Set RUST_OIDC_* values with services.stricknani.oidc.environmentFile, not extraConfig.";
      }
      {
        assertion = cfg.oidc.clientSecretFile == null || lib.hasPrefix "/" cfg.oidc.clientSecretFile;
        message = "services.stricknani.oidc.clientSecretFile must be an absolute runtime path.";
      }
      {
        assertion = !cfg.oidc.enable || cfg.oidc.providerId == "authelia";
        message = "services.stricknani.oidc.providerId must be authelia.";
      }
    ];

    systemd = {
      services.stricknani = {
        description = "Stricknani knitting project manager";
        after = [ "network-online.target" ];
        wants = [ "network-online.target" ];
        wantedBy = [ "multi-user.target" ];

        # Belt-and-suspenders: switch-to-configuration's unit-file-text diff
        # is supposed to catch an ExecStart path change on its own, but has
        # been observed (2026-09-23) to sometimes leave the old process
        # running after a successful `nixos-rebuild switch` that did update
        # the unit file on disk. Pinning restartTriggers to the package
        # derivation makes the restart decision explicit and independent of
        # that diffing, so a deploy can never again silently apply without
        # actually restarting the service.
        restartTriggers = [ cfg.package ];

        environment = {
          BIND_HOST = cfg.bindHost;
          BIND_PORT = toString cfg.port;
          RUST_DATABASE_URL = cfg.databaseUrl;
          RUST_MEDIA_ROOT = toString cfg.mediaRoot;
          TRUSTED_HOSTS = trustedHosts;
          SESSION_COOKIE_SECURE = lib.boolToString cfg.sessionCookieSecure;
          RUST_GRAVATAR_ENABLED = lib.boolToString cfg.gravatar.enable;
        }
        // oidcEnvironment
        // cfg.extraConfig;

        serviceConfig = {
          ExecStart = lib.getExe cfg.package;
          WorkingDirectory = cfg.dataDir;
          StateDirectory = "stricknani-rust";
          StateDirectoryMode = "0750";
          User = user;
          Group = group;
          Restart = "on-failure";
          RestartSec = 10;
          UMask = "0077";

          # Security hardening
          CapabilityBoundingSet = "";
          NoNewPrivileges = true;
          PrivateDevices = true;
          PrivateTmp = true;
          ProtectHome = true;
          ProtectSystem = "strict";
          ReadOnlyPaths = [ "/" ];
          ReadWritePaths =
            if toString cfg.mediaRoot == "${cfg.dataDir}/media" then
              [ cfg.dataDir ]
            else
              lib.unique [
                cfg.dataDir
                cfg.mediaRoot
              ];
          UnsetEnvironment = [
            "DATABASE_URL"
            "MEDIA_ROOT"
          ];
          EnvironmentFile =
            lib.optional (cfg.secretKeyFile != null) cfg.secretKeyFile
            ++ lib.optionals (cfg.oidc.enable && cfg.oidc.environmentFile != null) [
              cfg.oidc.environmentFile
            ]
            ++ [ oidcEnabledEnvironmentFile ];
        };
      };

      services.stricknani-backup = lib.mkIf cfg.backup.enable {
        description = "Stricknani database backup";
        after = [ "stricknani.service" ];
        wants = [ "stricknani.service" ];

        path = [
          pkgs.coreutils
          pkgs.gzip
          pkgs.gnutar
          pkgs.sqlite
        ];

        serviceConfig = {
          Type = "oneshot";
          ExecStart = backupScript;
          User = user;
          Group = group;
          UMask = "0077";
          ReadOnlyPaths = [ "/" ];
          ReadWritePaths = [
            cfg.dataDir
            cfg.backup.dir
          ];
        };
      };

      timers.stricknani-backup = lib.mkIf cfg.backup.enable {
        description = "Run Stricknani database backups";
        wantedBy = [ "timers.target" ];
        timerConfig = {
          OnCalendar = cfg.backup.schedule;
          Persistent = true;
          RandomizedDelaySec = "15m";
        };
      };

      tmpfiles.rules = [
        "d ${cfg.dataDir} 0750 ${user} ${group} -"
        "d ${cfg.mediaRoot} 0750 ${user} ${group} -"
      ]
      ++ lib.optional cfg.backup.enable "d ${cfg.backup.dir} 0750 ${user} ${group} -";
    };

    services.nginx.virtualHosts = lib.mkIf (cfg.nginx.enable && cfg.hostName != null) {
      "${cfg.hostName}" = {
        inherit (cfg.nginx) forceSSL enableACME;
        inherit (cfg) serverAliases;
        locations = {
          "/" = {
            proxyPass = "http://${cfg.bindHost}:${toString cfg.port}";
            recommendedProxySettings = true;
            proxyWebsockets = true;
          };
        }
        // lib.optionalAttrs cfg.oidc.enable {
          "/api/v2/auth/oidc/callback/" = {
            proxyPass = "http://${cfg.bindHost}:${toString cfg.port}";
            recommendedProxySettings = true;
            proxyWebsockets = true;
            extraConfig = "access_log off;";
          };
        };
      };
    };

    services.monit.config = lib.mkIf (cfg.hostName != null && config.services.monit.enable) (
      lib.mkAfter ''
        check host "stricknani" with address "${cfg.hostName}"
          group services
          restart program = "${pkgs.systemd}/bin/systemctl restart stricknani"
          # A deploy-triggered restart can fail one healthcheck cycle before
          # the app is ready. Requiring 2 consecutive failed cycles (~2 minutes)
          # avoids monit firing its own concurrent `systemctl restart stricknani`
          # while nixos-rebuild switch's restart of the same unit is still in
          # flight -- that race left the service bound to the pre-switch build
          # at least twice (T105).
          if failed
            port 443
            protocol https
            request "/healthz"
            with timeout 15 seconds
          for 2 cycles
          then restart
          if 5 restarts within 10 cycles then alert
      ''
    );

    users.users."${user}" = {
      isSystemUser = true;
      group = "stricknani";
      home = cfg.dataDir;
    };

    users.groups.stricknani = { };

    environment.systemPackages = [
      stricknaniCliWrapper
    ];
  };
}
