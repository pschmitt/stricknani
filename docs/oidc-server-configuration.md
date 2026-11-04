# Rust OIDC server configuration

OIDC is opt-in and applies only to the Rust backend. Keep the local password
login available when enabling it. If provider discovery is unavailable, the
Rust service still starts; OIDC requests return an unavailable response while
the local password site remains usable.

After database initialization, startup reconciles the configured provider IDs
without contacting the identity provider. Removing a provider revokes its
active OIDC-origin PATs. Startup stops if removing it would leave an active
password-disabled account without any configured linked provider. Re-enable
that provider or restore a local password login before removing it. A provider
network outage does not count as removal and does not revoke tokens. Reconcile
is transactional; a failed last-method check rolls back token revocation and
prevents startup with the requested configuration.

When the built-in Nginx vhost is enabled, its OIDC callback location disables
access logging because callback query strings carry one-time codes and state.
Any external proxy in front of Stricknani must apply equivalent query
redaction to callback access logs.

## NixOS options

The module exposes `services.stricknani.oidc.enable` and the optional
`providerId`, `issuer`, `clientId`, `clientSecretFile`, `callbackUri`, and
`nativeRedirectUris` settings. `providerId` is fixed to `authelia`. The other
typed values default to null, so deployments can provide them through a
protected runtime `environmentFile` instead. Values in that file override
same-name typed metadata. The module writes an explicit enabled/disabled
sentinel after the configured files, so `oidc.enable` controls whether OIDC is
active.

| Nix option | Environment variable | Notes |
| --- | --- | --- |
| `oidc.enable` | `OIDC_ENABLED` | Written last from a generated file, so it overrides `environmentFile`. Default false. |
| `oidc.providerId` | none | Fixed to `authelia`. |
| `oidc.issuer` | `OIDC_ISSUER` | |
| `oidc.clientId` | `OIDC_CLIENT_ID` | Not a secret. Mutually exclusive with `OIDC_CLIENT_ID_FILE`. |
| `oidc.clientSecretFile` | `OIDC_CLIENT_SECRET_FILE` | Absolute runtime path; the secret is never a Nix value. |
| `oidc.callbackUri` | `OIDC_CALLBACK_URI` | |
| `oidc.nativeRedirectUris` | `OIDC_NATIVE_REDIRECT_URIS` | List joined with commas. |
| `oidc.environmentFile` | systemd `EnvironmentFile` | Only loaded when `oidc.enable` is true. |

When OIDC is enabled, use secure session cookies. Supply an environment file or
all four required typed values: issuer, client ID, client secret file path, and
callback URI. Partial typed settings can be completed by the runtime file.
Never put a client secret value in a Nix option, Nix expression, or generated
environment file.

## Runtime environment

| Variable | Required | Purpose |
| --- | --- | --- |
| `OIDC_ENABLED` | Set by the Nix module | Explicit opt-in; the module's final value is authoritative. |
| `OIDC_ISSUER` | Yes when enabled | HTTPS issuer URL. |
| `OIDC_CLIENT_ID` or `OIDC_CLIENT_ID_FILE` | Exactly one | Client ID value or absolute path to a bounded runtime file containing it. |
| `OIDC_CLIENT_SECRET_FILE` | Yes when enabled | Absolute path to a runtime file containing the client secret. The file is limited to 4096 bytes. |
| `OIDC_CALLBACK_URI` | Yes when enabled | Exact HTTPS callback URI, including authority and port. Its authority must match an entry in `TRUSTED_HOSTS`. |
| `OIDC_NATIVE_REDIRECT_URIS` | No | Comma-separated exact native redirect allowlist. Defaults to `stricknani://oidc-callback`; HTTPS App Links are also accepted. |
| `OIDC_PAT_TTL_SECONDS` | No | PAT lifetime from 1 through 604800 seconds; defaults to 604800. |

The client ID file and client secret file must be absolute paths and contain no
more than 4096 bytes. Keep the secret file readable only by the service account
and provide its path at runtime. The Rust process zeroizes its in-memory client
secret on drop and never includes it in configuration errors or debug output.

Example values below use reserved test domains and synthetic data:

```ini
OIDC_ISSUER=https://id.example.test
OIDC_CLIENT_ID=synthetic-client
OIDC_CLIENT_SECRET_FILE=/run/credentials/stricknani/oidc-client-secret
OIDC_CALLBACK_URI=https://app.example.test/api/v2/auth/oidc/callback/authelia
OIDC_NATIVE_REDIRECT_URIS=stricknani://oidc-callback
OIDC_PAT_TTL_SECONDS=604800
```

The client ID can instead come from `OIDC_CLIENT_ID_FILE`; do not set both
client ID variables. If the runtime file sets `OIDC_CLIENT_ID_FILE`, do
not also set the typed `clientId` option. The callback path is fixed. HTTP
callbacks and HTTP test provider mode are not enabled in production
configuration.
