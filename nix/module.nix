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
  # Statuses nginx generates itself when the backend is down or unreachable.
  downStatuses = [
    "502"
    "503"
    "504"
  ];
  oidcEnabledEnvironmentFile = pkgs.writeText "stricknani-oidc-enabled.env" ''
    OIDC_ENABLED=${lib.boolToString cfg.oidc.enable}
  '';
  oidcEnvironment = lib.optionalAttrs cfg.oidc.enable (
    lib.optionalAttrs (cfg.oidc.issuer != null) {
      OIDC_ISSUER = cfg.oidc.issuer;
    }
    // lib.optionalAttrs (cfg.oidc.clientId != null) {
      OIDC_CLIENT_ID = cfg.oidc.clientId;
    }
    // lib.optionalAttrs (cfg.oidc.clientSecretFile != null) {
      OIDC_CLIENT_SECRET_FILE = cfg.oidc.clientSecretFile;
    }
    // lib.optionalAttrs (cfg.oidc.callbackUri != null) {
      OIDC_CALLBACK_URI = cfg.oidc.callbackUri;
    }
    // lib.optionalAttrs (cfg.oidc.nativeRedirectUris != null) {
      OIDC_NATIVE_REDIRECT_URIS = lib.concatStringsSep "," cfg.oidc.nativeRedirectUris;
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
  # Shared by the scheduled backup and the pre-upgrade backup. Expects DB_URL
  # and PROG (the message prefix) and sets DB_PATH, accepting both
  # `sqlite:/path` and `sqlite:///path`.
  sqliteUrlToPath = ''
    if [[ "$DB_URL" == sqlite:/* ]]
    then
      DB_PATH="''${DB_URL#sqlite:}"
      while [[ "$DB_PATH" == //* ]]
      do
        DB_PATH="''${DB_PATH#/}"
      done
      if [[ "$DB_URL" == *\?* || "$DB_URL" == *#* || "$DB_PATH" != /* ]]
      then
        echo "$PROG: unsupported SQLite URL" >&2
        exit 2
      fi
    else
      echo "$PROG: requires file-backed SQLite" >&2
      exit 2
    fi
  '';

  backupScript = pkgs.writeShellScript "stricknani-backup" ''
    set -euo pipefail
    umask 0077
    PROG=stricknani-backup

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

    ${sqliteUrlToPath}

    if [[ ! -f "$DB_PATH" ]]
    then
      echo "$PROG: sqlite database not found at '$DB_PATH'" >&2
      exit 2
    fi

    DB_SNAPSHOT="database.sqlite3"
    sqlite3 "$DB_PATH" ".backup $TMP_DIR/$DB_SNAPSHOT"

    if [[ -d "$MEDIA_DIR" ]]
    then
      tar -czf "$ARCHIVE_TMP" -C "$TMP_DIR" "$DB_SNAPSHOT" \
        -C "$(dirname "$MEDIA_DIR")" "$(basename "$MEDIA_DIR")"
    else
      echo "$PROG: media directory missing at '$MEDIA_DIR'" >&2
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

  # ExecStartPre of stricknani.service: when the package differs from the one
  # recorded by the last pre-upgrade backup, write a verified
  # `sqlite3 .backup` copy of the database first. The marker is written right
  # after the backup (not after a successful start), so a crash-looping new
  # version does not take a backup per attempt and push the real pre-upgrade
  # copy out of the retention window; a failed backup leaves the marker alone,
  # keeps the service from starting, and is retried.
  packageVersion = lib.strings.sanitizeDerivationName (cfg.package.version or "unknown");
  preUpgradeBackupScript = pkgs.writeShellScript "stricknani-predeploy-backup" ''
    set -euo pipefail
    umask 0077
    export PATH='${
      lib.makeBinPath [
        pkgs.coreutils
        pkgs.sqlite
      ]
    }'
    PROG=stricknani-predeploy-backup

    BACKUP_DIR='${cfg.backup.dir}'
    DB_URL='${cfg.databaseUrl}'
    RETENTION='${toString cfg.backup.beforeUpgrade.retention}'
    PACKAGE='${cfg.package}'
    VERSION='${packageVersion}'
    MARKER='${cfg.dataDir}/.predeploy-backup-package'

    ${sqliteUrlToPath}

    write_marker() {
      printf '%s\n' "$PACKAGE" > "$MARKER.tmp"
      mv -f -- "$MARKER.tmp" "$MARKER"
    }

    if [[ -f "$MARKER" && "$(< "$MARKER")" == "$PACKAGE" ]]
    then
      exit 0
    fi

    if [[ ! -f "$DB_PATH" ]]
    then
      # First installation: nothing to protect yet.
      write_marker
      exit 0
    fi

    mkdir -p -- "$BACKUP_DIR"
    TIMESTAMP="$(date -u +%Y%m%d-%H%M%S)"
    TARGET="$BACKUP_DIR/predeploy-$VERSION-$TIMESTAMP.sqlite3"
    PARTIAL="$BACKUP_DIR/.predeploy-$VERSION-$TIMESTAMP.partial"
    trap 'rm -f -- "$PARTIAL"' EXIT

    rm -f -- "$PARTIAL"
    sqlite3 "$DB_PATH" ".backup '$PARTIAL'"
    RESULT="$(sqlite3 "$PARTIAL" 'PRAGMA integrity_check;')"
    if [[ "$RESULT" != ok ]]
    then
      echo "$PROG: integrity check of the new backup failed" >&2
      exit 1
    fi
    mv -- "$PARTIAL" "$TARGET"
    echo "$PROG: wrote $TARGET ($(stat -c %s "$TARGET") bytes) before starting $PACKAGE"
    write_marker

    # Keep the newest RETENTION predeploy-* backups (by the UTC timestamp in
    # the name); other files are never touched.
    mapfile -t OLD < <(
      for FILE in "$BACKUP_DIR"/predeploy-*.sqlite3
      do
        [[ -f "$FILE" ]] || continue
        NAME="''${FILE##*/}"
        NAME="''${NAME%.sqlite3}"
        STAMP="''${NAME: -15}"
        [[ "$STAMP" =~ ^[0-9]{8}-[0-9]{6}$ ]] || continue
        printf '%s %s\n' "$STAMP" "$FILE"
      done | sort | head -n "-$RETENTION" | cut -d' ' -f2-
    )
    for FILE in "''${OLD[@]}"
    do
      rm -f -- "$FILE"
    done
  '';

  stricknaniCliWrapper = pkgs.writeShellScriptBin "stricknani-cli" ''
    exec sudo -u "${user}" -- env \
      -u DATABASE_URL -u MEDIA_ROOT \
      STRICKNANI_DATABASE_URL="${cfg.databaseUrl}" \
      STRICKNANI_MEDIA_ROOT="${cfg.mediaRoot}" \
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

    gravatar.enable = lib.mkOption {
      type = lib.types.bool;
      default = true;
      description = "Let the server fetch Gravatar images (SHA-256 of the account email) for users without an uploaded avatar. Clients never contact Gravatar. Sets GRAVATAR_ENABLED; users can still opt out individually.";
    };

    imports.enable = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = "Let the server fetch public web pages for importing projects and yarns from a link, and download the pictures found there. Sets EXTERNAL_IMPORTS_ENABLED. Pages are fetched with address checks that block private networks.";
    };

    ai = {
      enable = lib.mkOption {
        type = lib.types.bool;
        default = false;
        description = "Let users ask an AI model to fill gaps in an import draft. Sets AI_ENABLED. Needs ai.apiKeyFile or an API key in secretKeyFile. Without it imports still work with rule-based extraction. The page text of imported links is sent to the model.";
      };

      provider = lib.mkOption {
        type = lib.types.enum [
          "openai"
          "openrouter"
          "groq"
          "custom"
        ];
        default = "openai";
        description = "Provider for the chat-completions API. Use custom for any other OpenAI-compatible endpoint, such as a local agent gateway, together with ai.baseUrl.";
      };

      apiKeyFile = lib.mkOption {
        type = lib.types.nullOr lib.types.path;
        default = null;
        description = "Path to a file containing only the API key, readable by the service user. Sets AI_API_KEY_FILE.";
      };

      baseUrl = lib.mkOption {
        type = lib.types.nullOr lib.types.str;
        default = null;
        example = "http://127.0.0.1:8642/v1";
        description = "Base URL of an OpenAI-compatible API (AI_BASE_URL). Required for provider custom. Plain HTTP is accepted only for localhost.";
      };

      model = lib.mkOption {
        type = lib.types.nullOr lib.types.str;
        default = null;
        description = "Model name (AI_MODEL). Defaults to the provider default; hermes-agent for custom.";
      };

      timeoutSeconds = lib.mkOption {
        type = lib.types.nullOr lib.types.ints.positive;
        default = null;
        description = "Timeout for one AI request (AI_TIMEOUT_SECONDS, 5 to 300). Agent gateways can need a minute.";
      };

      browse = lib.mkOption {
        type = lib.types.bool;
        default = false;
        description = "Let the AI read link imports with a browser (AI_BROWSE). Only useful with an agent gateway that has a browser tool, such as Hermes with a Playwright MCP server. Blocked or JavaScript-rendered pages are then read by the model instead of failing, and those fields are marked as AI-read for review.";
      };

      browseTimeoutSeconds = lib.mkOption {
        type = lib.types.ints.between 30 200;
        default = 180;
        description = "Timeout for one AI request while ai.browse is on (AI_BROWSE_TIMEOUT_SECONDS). Also raises the nginx proxy read timeout of the Stricknani host.";
      };
    };

    ravelry.credentialsFile = lib.mkOption {
      type = lib.types.nullOr lib.types.path;
      default = null;
      description = "Environment file with RAVELRY_USERNAME and RAVELRY_PASSWORD, the read-only Basic-auth key pair of a Ravelry API app. Enables clean gauge, needle, yarn and fiber data when importing Ravelry pattern and yarn links.";
    };

    android.appLinkFingerprints = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ ];
      example = [
        "61:50:52:4C:34:01:C2:32:A8:B5:82:E8:73:D6:92:E7:1A:D9:C6:72:63:17:FC:6C:69:88:90:40:F6:0D:A1:1F"
      ];
      description = "SHA-256 fingerprints of the signing certificates of the Android app builds that may open this instance's project and yarn links directly (Android App Links, served as /.well-known/assetlinks.json). Sets ANDROID_APP_LINK_FINGERPRINTS. Empty: links open in the browser.";
    };

    registration.enable = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = "Allow anyone who can reach the instance to create their own account (web and Android). Sets SIGNUP_ENABLED. Leave this off for a private instance; accounts are then created by an administrator or with stricknani-cli create-user.";
    };

    sentry = {
      dsnFile = lib.mkOption {
        type = lib.types.nullOr lib.types.str;
        default = null;
        description = "Absolute runtime path of a file holding the Sentry DSN (never a Nix value). Setting it turns on optional error reporting (STRICKNANI_SENTRY_DSN_FILE); without it nothing is sent anywhere. Users can opt out in their account settings.";
      };
      frontendDsnFile = lib.mkOption {
        type = lib.types.nullOr lib.types.str;
        default = null;
        description = "Optional absolute runtime path of a file holding a separate Sentry DSN for the web and Android apps (STRICKNANI_SENTRY_FRONTEND_DSN_FILE). Defaults to the dsnFile DSN, so one project can serve everything; events carry a component tag (server, web, android).";
      };
      environment = lib.mkOption {
        type = lib.types.str;
        default = "production";
        description = "Sentry environment name (STRICKNANI_SENTRY_ENVIRONMENT). Only used with a DSN file.";
      };
      tracesSampleRate = lib.mkOption {
        type = lib.types.numbers.between 0 1;
        default = 0;
        description = "Share of requests traced for performance monitoring (STRICKNANI_SENTRY_TRACES_SAMPLE_RATE); 0 reports errors only. Only used with a DSN file.";
      };
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
      default = "sqlite:${cfg.dataDir}/stricknani.sqlite3";
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
        description = "Directory where backups are stored: scheduled archives, pre-upgrade and pre-migration copies (also passed to the server as `STRICKNANI_BACKUP_DIR`).";
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

      beforeUpgrade = {
        enable = lib.mkOption {
          type = lib.types.bool;
          default = true;
          description = ''
            Before the service starts with a different package than the one
            of the previous pre-upgrade backup, write a verified
            `sqlite3 .backup` copy of the database to
            `<backup.dir>/predeploy-<version>-<UTC timestamp>.sqlite3`. If the
            backup fails, the service does not start.
          '';
        };

        retention = lib.mkOption {
          type = lib.types.ints.positive;
          default = 10;
          description = "How many `predeploy-*` backups to keep.";
        };
      };

      beforeMigration = {
        enable = lib.mkOption {
          type = lib.types.bool;
          default = true;
          description = ''
            Let the server snapshot the database (`VACUUM INTO`) to
            `<backup.dir>/pre-migrate-*.sqlite3` before it applies pending
            schema migrations (`STRICKNANI_PRE_MIGRATE_BACKUP`).
          '';
        };

        retention = lib.mkOption {
          type = lib.types.ints.positive;
          default = 10;
          description = "How many `pre-migrate-*` snapshots to keep (`STRICKNANI_PRE_MIGRATE_BACKUP_RETENTION`).";
        };
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
      maxRestoreSize = lib.mkOption {
        type = lib.types.str;
        default = "2g";
        example = "512m";
        description = "Largest request body nginx accepts on `/api/v2/account/import` (a data export restore), as an nginx size (`client_max_body_size`). Request buffering is off there so the archive streams to the server. The server itself caps a restore at 2 GiB.";
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
        assertion = loopbackBind || cfg.sessionCookieSecure;
        message = "services.stricknani requires secure session cookies for non-loopback listeners.";
      }
      {
        assertion = loopbackBind || cfg.hostName != null || cfg.serverAliases != [ ];
        message = "services.stricknani requires a hostname or server alias for non-loopback listeners.";
      }
      {
        assertion = !cfg.ai.enable || cfg.ai.apiKeyFile != null || cfg.secretKeyFile != null;
        message = "services.stricknani.ai.enable requires ai.apiKeyFile (or an API key in secretKeyFile).";
      }
      {
        assertion = !cfg.ai.enable || cfg.ai.provider != "custom" || cfg.ai.baseUrl != null;
        message = "services.stricknani.ai.provider = \"custom\" requires ai.baseUrl.";
      }
      {
        assertion =
          !(cfg.extraConfig ? STRICKNANI_DATABASE_URL) && !(cfg.extraConfig ? STRICKNANI_MEDIA_ROOT);
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
        assertion = builtins.all (name: !(lib.hasPrefix "OIDC_" name)) (builtins.attrNames cfg.extraConfig);
        message = "Set OIDC_* values with services.stricknani.oidc.environmentFile, not extraConfig.";
      }
      {
        assertion = cfg.oidc.clientSecretFile == null || lib.hasPrefix "/" cfg.oidc.clientSecretFile;
        message = "services.stricknani.oidc.clientSecretFile must be an absolute runtime path.";
      }
      {
        assertion = lib.all (file: file == null || lib.hasPrefix "/" file) [
          cfg.sentry.dsnFile
          cfg.sentry.frontendDsnFile
        ];
        message = "services.stricknani.sentry.dsnFile and frontendDsnFile must be absolute runtime paths.";
      }
      {
        assertion = !(cfg.extraConfig ? STRICKNANI_SENTRY_DSN);
        message = "Set the Sentry DSN with services.stricknani.sentry.dsnFile, not extraConfig.";
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
          STRICKNANI_DATABASE_URL = cfg.databaseUrl;
          STRICKNANI_MEDIA_ROOT = toString cfg.mediaRoot;
          STRICKNANI_BACKUP_DIR = cfg.backup.dir;
          STRICKNANI_PRE_MIGRATE_BACKUP = lib.boolToString cfg.backup.beforeMigration.enable;
          STRICKNANI_PRE_MIGRATE_BACKUP_RETENTION = toString cfg.backup.beforeMigration.retention;
          TRUSTED_HOSTS = trustedHosts;
          SESSION_COOKIE_SECURE = lib.boolToString cfg.sessionCookieSecure;
          GRAVATAR_ENABLED = lib.boolToString cfg.gravatar.enable;
          SIGNUP_ENABLED = lib.boolToString cfg.registration.enable;
          EXTERNAL_IMPORTS_ENABLED = lib.boolToString cfg.imports.enable;
          AI_ENABLED = lib.boolToString cfg.ai.enable;
        }
        // lib.optionalAttrs (cfg.android.appLinkFingerprints != [ ]) {
          ANDROID_APP_LINK_FINGERPRINTS = lib.concatStringsSep "," cfg.android.appLinkFingerprints;
        }
        // lib.optionalAttrs (cfg.sentry.dsnFile != null) {
          STRICKNANI_SENTRY_DSN_FILE = cfg.sentry.dsnFile;
        }
        // lib.optionalAttrs (cfg.sentry.frontendDsnFile != null) {
          STRICKNANI_SENTRY_FRONTEND_DSN_FILE = cfg.sentry.frontendDsnFile;
        }
        // lib.optionalAttrs (cfg.sentry.dsnFile != null || cfg.sentry.frontendDsnFile != null) {
          STRICKNANI_SENTRY_ENVIRONMENT = cfg.sentry.environment;
          STRICKNANI_SENTRY_TRACES_SAMPLE_RATE = toString cfg.sentry.tracesSampleRate;
        }
        // lib.optionalAttrs cfg.ai.enable (
          {
            AI_PROVIDER = cfg.ai.provider;
          }
          // lib.optionalAttrs (cfg.ai.apiKeyFile != null) { AI_API_KEY_FILE = toString cfg.ai.apiKeyFile; }
          // lib.optionalAttrs (cfg.ai.baseUrl != null) { AI_BASE_URL = cfg.ai.baseUrl; }
          // lib.optionalAttrs (cfg.ai.model != null) { AI_MODEL = cfg.ai.model; }
          // lib.optionalAttrs cfg.ai.browse {
            AI_BROWSE = "true";
            AI_BROWSE_TIMEOUT_SECONDS = toString cfg.ai.browseTimeoutSeconds;
          }
          // lib.optionalAttrs (cfg.ai.timeoutSeconds != null) {
            AI_TIMEOUT_SECONDS = toString cfg.ai.timeoutSeconds;
          }
        )
        // oidcEnvironment
        // cfg.extraConfig;

        serviceConfig = {
          ExecStartPre = lib.optional cfg.backup.beforeUpgrade.enable "${preUpgradeBackupScript}";
          ExecStart = lib.getExe cfg.package;
          WorkingDirectory = cfg.dataDir;
          # systemd creates a data directory under /var/lib; others come from tmpfiles.
          StateDirectory = lib.mkIf (lib.hasPrefix "/var/lib/" (toString cfg.dataDir)) (
            lib.removePrefix "/var/lib/" (toString cfg.dataDir)
          );
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
          # The backup directory is written by the pre-upgrade backup, the
          # server's pre-migration snapshots and restores.
          ReadWritePaths = lib.unique [
            cfg.dataDir
            (toString cfg.mediaRoot)
            cfg.backup.dir
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
            ++ lib.optional (cfg.ravelry.credentialsFile != null) cfg.ravelry.credentialsFile
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
      ++ [ "d ${cfg.backup.dir} 0750 ${user} ${group} -" ];
    };

    services.nginx.virtualHosts = lib.mkIf (cfg.nginx.enable && cfg.hostName != null) {
      "${cfg.hostName}" = {
        inherit (cfg.nginx) forceSSL enableACME;
        inherit (cfg) serverAliases;
        # When the backend is down nginx answers 502/503/504 itself. Those get the
        # playful static pages shipped in the web package instead of nginx's grey
        # default. Backend responses (including its own 5xx) pass through untouched,
        # because proxy_intercept_errors stays off, and the maintenance page of the
        # deployment (a `return 503 '<html>'`) is unaffected for the same reason.
        extraConfig = lib.concatMapStringsSep "\n" (
          status: "error_page ${status} @stricknani_down_${status};"
        ) downStatuses;
        locations = {
          "/" = {
            proxyPass = "http://${cfg.bindHost}:${toString cfg.port}";
            recommendedProxySettings = true;
            proxyWebsockets = true;
            # A browsing AI import can run for the whole browse timeout; nginx defaults to 60 s.
            extraConfig = lib.optionalString (cfg.ai.enable && cfg.ai.browse) ''
              proxy_read_timeout ${toString (cfg.ai.browseTimeoutSeconds + 40)}s;
            '';
          };
        }
        // lib.genAttrs (map (status: "@stricknani_down_${status}") downStatuses) (name: {
          # A static file only answers GET and HEAD (nginx would turn a POST into a 405),
          # so every other method gets a short plain-text body with the original status.
          # The body matters: a bare `return 502;` would be sent through error_page again.
          extraConfig =
            let
              status = lib.removePrefix "@stricknani_down_" name;
            in
            ''
              default_type text/plain;
              if ($request_method !~ ^(GET|HEAD)$) {
                return ${status} "${status}\n";
              }
              root ${cfg.package}/share/stricknani/web;
              rewrite ^ /errors/${status}.html break;
              add_header Cache-Control "no-store" always;
            '';
        })
        // {
          # Restoring a data export uploads one archive of up to the server's 2 GiB cap: lift
          # nginx's body limit for this one path, stream it, and give a slow upload time.
          "= /api/v2/account/import" = {
            proxyPass = "http://${cfg.bindHost}:${toString cfg.port}";
            recommendedProxySettings = true;
            extraConfig = ''
              client_max_body_size ${cfg.nginx.maxRestoreSize};
              proxy_request_buffering off;
              proxy_read_timeout 600s;
              proxy_send_timeout 600s;
            '';
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
