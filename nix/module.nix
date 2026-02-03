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

  stricknaniCliWrapper = pkgs.writeShellScriptBin "stricknani-cli" ''
    exec sudo -u "${user}" -- env \
      DATABASE_URL="${cfg.databaseUrl}" \
      ${pkgs.runtimeShell} -c ' \
        cd "${cfg.dataDir}" \
        exec ${cfg.package}/bin/stricknani-cli "$@" \
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
    systemd.services.stricknani = {
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
