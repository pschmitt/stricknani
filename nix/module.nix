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
  rustBackend = cfg.backend == "rust";
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

    ${
      if rustBackend then
        ''
          if [[ "$DB_URL" == sqlite:/* ]]
          then
            DB_PATH="''${DB_URL#sqlite:}"
            while [[ "$DB_PATH" == //* ]]
            do
              DB_PATH="''${DB_PATH#/}"
            done
            if [[ "$DB_URL" == *\?* || "$DB_URL" == *#* || "$DB_PATH" != /* ]]
            then
              echo "stricknani-backup: unsupported Rust SQLite URL" >&2
              exit 2
            fi
          else
            echo "stricknani-backup: Rust backend requires file-backed SQLite" >&2
            exit 2
          fi
        ''
      else
        ''
          if [[ "$DB_URL" == sqlite:///* ]]
          then
            DB_PATH="''${DB_URL#sqlite:///}"
        ''
    }

      if [[ ! -f "$DB_PATH" ]]
      then
        echo "stricknani-backup: sqlite database not found at '$DB_PATH'" >&2
        exit 2
      fi

      DB_SNAPSHOT="database.sqlite3"
      sqlite3 "$DB_PATH" ".backup $TMP_DIR/$DB_SNAPSHOT"
    ${
      if rustBackend then
        ""
      else
        ''
          elif [[ "$DB_URL" == postgresql://* || "$DB_URL" == postgres://* ]]
          then
            DB_SNAPSHOT="database.sql"
            pg_dump "$DB_URL" > "$TMP_DIR/$DB_SNAPSHOT"
          else
            echo "stricknani-backup: unsupported DATABASE_URL scheme in '$DB_URL'" >&2
            exit 2
          fi
        ''
    }

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
      ${lib.optionalString rustBackend "-u DATABASE_URL -u MEDIA_ROOT \\"}
      ${
        if rustBackend then
          ''
            RUST_DATABASE_URL="${cfg.databaseUrl}" \
            RUST_MEDIA_ROOT="${cfg.mediaRoot}" \
          ''
        else
          ''
            DATABASE_URL="${cfg.databaseUrl}" \
          ''
      }
      ${pkgs.runtimeShell} -c '
        cd "${cfg.dataDir}";
        exec "${cfg.package}/bin/${
          if rustBackend then "stricknani-server" else "stricknani-cli"
        }" "$@" \
      ' -- "$@"
  '';
in
{
  options.services.stricknani = {
    enable = lib.mkEnableOption "Stricknani knitting project manager";

    backend = lib.mkOption {
      type = lib.types.enum [
        "python"
        "rust"
      ];
      default = "rust";
      description = "Implementation used by the Stricknani service.";
    };

    package = lib.mkOption {
      type = lib.types.package;
      default =
        if cfg.backend == "rust" then
          pkgs.callPackage ./rust-package.nix {
            corePackage = pkgs.callPackage ./rust-core-package.nix { };
            webPackage = pkgs.callPackage ./web-package.nix { };
          }
        else
          pkgs.callPackage ./package.nix {
            python3 = pkgs.python313;
            fastapi-csrf-protect = pkgs.python313.pkgs.callPackage ./fastapi-csrf-protect.nix { };
          };
      description = "The Stricknani package to use; defaults to the selected backend.";
    };

    dataDir = lib.mkOption {
      type = lib.types.path;
      default = if cfg.backend == "rust" then "/var/lib/stricknani-rust" else "/var/lib/stricknani";
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

    databaseUrl = lib.mkOption {
      type = lib.types.str;
      default =
        if cfg.backend == "rust" then
          "sqlite:${cfg.dataDir}/rust.sqlite3"
        else
          "sqlite:///${cfg.dataDir}/stricknani.db";
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
          !rustBackend
          || (
            lib.hasPrefix "sqlite:/" cfg.databaseUrl
            && !(lib.hasInfix "?" cfg.databaseUrl)
            && !(lib.hasInfix "#" cfg.databaseUrl)
          );
        message = "services.stricknani Rust backend requires a file-backed SQLite databaseUrl without query or fragment.";
      }
      {
        assertion = !rustBackend || lib.hasPrefix "/" (toString cfg.mediaRoot);
        message = "services.stricknani Rust backend requires an absolute mediaRoot.";
      }
      {
        assertion = !rustBackend || toString cfg.dataDir != "/var/lib/stricknani";
        message = "services.stricknani Rust backend requires a dataDir separate from the Python default.";
      }
      {
        assertion = !rustBackend || loopbackBind || cfg.sessionCookieSecure;
        message = "services.stricknani Rust backend requires secure session cookies for non-loopback listeners.";
      }
      {
        assertion = !rustBackend || loopbackBind || cfg.hostName != null || cfg.serverAliases != [ ];
        message = "services.stricknani Rust backend requires a hostname or server alias for non-loopback listeners.";
      }
      {
        assertion =
          !rustBackend || (!(cfg.extraConfig ? RUST_DATABASE_URL) && !(cfg.extraConfig ? RUST_MEDIA_ROOT));
        message = "Set Rust database and media paths with databaseUrl and mediaRoot, not extraConfig.";
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

        environment =
          (
            if rustBackend then
              {
                BIND_HOST = cfg.bindHost;
                BIND_PORT = toString cfg.port;
                RUST_DATABASE_URL = cfg.databaseUrl;
                RUST_MEDIA_ROOT = toString cfg.mediaRoot;
                TRUSTED_HOSTS = trustedHosts;
                SESSION_COOKIE_SECURE = lib.boolToString cfg.sessionCookieSecure;
              }
            else
              {
                PORT = toString cfg.port;
                BIND_HOST = cfg.bindHost;
                DATABASE_URL = cfg.databaseUrl;
                MEDIA_ROOT = toString cfg.mediaRoot;
                ALLOWED_HOSTS = trustedHosts;
              }
          )
          // cfg.extraConfig;

        serviceConfig = {
          ExecStart = lib.getExe cfg.package;
          WorkingDirectory = cfg.dataDir;
          StateDirectory = if rustBackend then "stricknani-rust" else "stricknani";
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
          UnsetEnvironment = lib.optionals rustBackend [
            "DATABASE_URL"
            "MEDIA_ROOT"
          ];
          EnvironmentFile = lib.optional (cfg.secretKeyFile != null) cfg.secretKeyFile;
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
        ]
        ++ lib.optional (!rustBackend) pkgs.postgresql;

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

      tmpfiles.rules =
        lib.optionals rustBackend [
          "d ${cfg.dataDir} 0750 ${user} ${group} -"
          "d ${cfg.mediaRoot} 0750 ${user} ${group} -"
        ]
        ++ lib.optional cfg.backup.enable "d ${cfg.backup.dir} 0750 ${user} ${group} -";
    };

    services.nginx.virtualHosts = lib.mkIf (cfg.nginx.enable && cfg.hostName != null) {
      "${cfg.hostName}" = {
        inherit (cfg.nginx) forceSSL enableACME;
        inherit (cfg) serverAliases;
        locations."/" = {
          proxyPass = "http://${cfg.bindHost}:${toString cfg.port}";
          recommendedProxySettings = true;
          proxyWebsockets = true;
        };
      };
    };

    services.monit.config = lib.mkIf (cfg.hostName != null && config.services.monit.enable) (
      lib.mkAfter ''
        check host "stricknani" with address "${cfg.hostName}"
          group services
          restart program = "${pkgs.systemd}/bin/systemctl restart stricknani"
          # Startup takes ~15-25s (scikit-image/pymupdf/weasyprint imports), so a
          # deploy-triggered restart routinely fails one healthcheck cycle before
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
