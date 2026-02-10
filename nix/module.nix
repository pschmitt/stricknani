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
  backupScript = pkgs.writeShellScript "stricknani-backup" ''
    set -euo pipefail
    umask 0077

    BACKUP_DIR="${cfg.dataDir}/backups"
    MEDIA_DIR="${cfg.dataDir}/media"
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

    if [[ "$DB_URL" == sqlite:///* ]]
    then
      DB_PATH="''${DB_URL#sqlite:///}"

      if [[ ! -f "$DB_PATH" ]]
      then
        echo "stricknani-backup: sqlite database not found at '$DB_PATH'" >&2
        exit 2
      fi

      DB_SNAPSHOT="database.sqlite3"
      sqlite3 "$DB_PATH" ".backup $TMP_DIR/$DB_SNAPSHOT"
    elif [[ "$DB_URL" == postgresql://* || "$DB_URL" == postgres://* ]]
    then
      DB_SNAPSHOT="database.sql"
      pg_dump "$DB_URL" > "$TMP_DIR/$DB_SNAPSHOT"
    else
      echo "stricknani-backup: unsupported DATABASE_URL scheme in '$DB_URL'" >&2
      exit 2
    fi

    if [[ -d "$MEDIA_DIR" ]]
    then
      tar -czf "$ARCHIVE_TMP" -C "$TMP_DIR" "$DB_SNAPSHOT" -C "${cfg.dataDir}" media
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
      DATABASE_URL="${cfg.databaseUrl}" \
      ${pkgs.runtimeShell} -c '
        cd "${cfg.dataDir}";
        exec "${cfg.package}/bin/stricknani-cli" "$@" \
      ' -- "$@"
  '';
in
{
  options.services.stricknani = {
    enable = lib.mkEnableOption "Stricknani knitting project manager";

    package = lib.mkOption {
      type = lib.types.package;
      default = pkgs.stricknani;
      description = "The stricknani package to use.";
    };

    dataDir = lib.mkOption {
      type = lib.types.path;
      default = "/var/lib/stricknani";
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
      default = "sqlite:///${cfg.dataDir}/stricknani.db";
      description = "The database connection string.";
    };

    backup = {
      enable = lib.mkOption {
        type = lib.types.bool;
        default = true;
        description = "Enable scheduled Stricknani database backups.";
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
    systemd = {
      services.stricknani = {
        description = "Stricknani knitting project manager";
        after = [ "network-online.target" ];
        wants = [ "network-online.target" ];
        wantedBy = [ "multi-user.target" ];

        environment = {
          PORT = toString cfg.port;
          BIND_HOST = cfg.bindHost;
          DATABASE_URL = cfg.databaseUrl;
          MEDIA_ROOT = "${cfg.dataDir}/media";
          ALLOWED_HOSTS = lib.concatStringsSep "," (
            lib.optional (cfg.hostName != null) cfg.hostName
            ++ cfg.serverAliases
            ++ [
              "127.0.0.1"
              "localhost"
            ]
          );
        } // cfg.extraConfig;

        serviceConfig = {
          ExecStart = lib.getExe cfg.package;
          WorkingDirectory = cfg.dataDir;
          StateDirectory = "stricknani";
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
          ReadWritePaths = [ cfg.dataDir ];
          EnvironmentFile = lib.optional (cfg.secretKeyFile != null) cfg.secretKeyFile;
        };
      };

      services.stricknani-backup = lib.mkIf cfg.backup.enable {
        description = "Stricknani database backup";
        after = [ "stricknani.service" ];
        wants = [ "stricknani.service" ];

        path = with pkgs; [
          coreutils
          gzip
          gnutar
          postgresql
          sqlite
        ];

        serviceConfig = {
          Type = "oneshot";
          ExecStart = backupScript;
          User = user;
          Group = group;
          UMask = "0077";
          ReadOnlyPaths = [ "/" ];
          ReadWritePaths = [ cfg.dataDir ];
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

      tmpfiles.rules = lib.mkIf cfg.backup.enable [
        "d ${cfg.dataDir}/backups 0750 ${user} ${group} -"
      ];
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

    services.monit.config = lib.mkIf (cfg.hostName != null && config.services.monit.enable) (lib.mkAfter ''
      check host "stricknani" with address "${cfg.hostName}"
        group services
        restart program = "${pkgs.systemd}/bin/systemctl restart stricknani"
        if failed
          port 443
          protocol https
          request "/healthz"
          with timeout 15 seconds
        then restart
        if 5 restarts within 10 cycles then alert
    '');

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
