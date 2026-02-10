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

    backup_dir="${cfg.dataDir}/backups"
    media_dir="${cfg.dataDir}/media"
    db_url='${cfg.databaseUrl}'
    retention='${toString cfg.backup.retention}'
    timestamp="$(date -u +%Y%m%d-%H%M%S)"
    archive_tmp="$backup_dir/stricknani-$timestamp.tar.gz.tmp"
    archive_file="$backup_dir/stricknani-$timestamp.tar.gz"

    mkdir -p "$backup_dir"
    tmp_dir="$(mktemp -d "$backup_dir/.stricknani-backup-$timestamp.XXXXXX")"
    cleanup() {
      rm -rf -- "$tmp_dir"
      rm -f -- "$archive_tmp"
    }
    trap cleanup EXIT

    if [[ "$db_url" == sqlite:///* ]]
    then
      db_path="''${db_url#sqlite:///}"
      if [[ ! -f "$db_path" ]]
      then
        echo "stricknani-backup: sqlite database not found at '$db_path'" >&2
        exit 2
      fi

      db_snapshot="database.sqlite3"
      sqlite3 "$db_path" ".backup $tmp_dir/$db_snapshot"
    elif [[ "$db_url" == postgresql://* || "$db_url" == postgres://* ]]
    then
      db_snapshot="database.sql"
      pg_dump "$db_url" > "$tmp_dir/$db_snapshot"
    else
      echo "stricknani-backup: unsupported DATABASE_URL scheme in '$db_url'" >&2
      exit 2
    fi

    if [[ -d "$media_dir" ]]
    then
      tar -czf "$archive_tmp" -C "$tmp_dir" "$db_snapshot" -C "${cfg.dataDir}" media
    else
      echo "stricknani-backup: media directory missing at '$media_dir'" >&2
      tar -czf "$archive_tmp" -C "$tmp_dir" "$db_snapshot"
    fi
    mv "$archive_tmp" "$archive_file"
    trap - EXIT
    rm -rf -- "$tmp_dir"

    mapfile -t backups < <(ls -1dt "$backup_dir"/stricknani-* 2>/dev/null || true)
    if (( ''${#backups[@]} > retention ))
    then
      for old_backup in "''${backups[@]:retention}"
      do
        rm -f -- "$old_backup"
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
