{
  config,
  lib,
  pkgs,
  ...
}:
let
  cfg = config.services.stricknani;
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

    secretKeyFile = lib.mkOption {
      type = lib.types.nullOr lib.types.path;
      default = null;
      description = "Path to a file containing the secret key for sessions.";
    };

    databaseUrl = lib.mkOption {
      type = lib.types.str;
      default = "sqlite:///${cfg.dataDir}/stricknani.db";
      description = "The database connection string.";
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
      after = [ "network.target" ];
      wantedBy = [ "multi-user.target" ];

      environment = {
        PORT = toString cfg.port;
        BIND_HOST = cfg.bindHost;
        DATABASE_URL = cfg.databaseUrl;
        MEDIA_ROOT = "${cfg.dataDir}/media";
      } // cfg.extraConfig;

      serviceConfig = {
        ExecStart = "${cfg.package}/bin/stricknani";
        WorkingDirectory = cfg.dataDir;
        StateDirectory = "stricknani";
        User = "stricknani";
        Group = "stricknani";
        Restart = "always";

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

    users.users.stricknani = {
      isSystemUser = true;
      group = "stricknani";
      home = cfg.dataDir;
    };

    users.groups.stricknani = { };
  };
}
