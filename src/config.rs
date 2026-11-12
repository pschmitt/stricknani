use std::env;
use std::error::Error;
use std::fmt;
use std::fs::File;
use std::io::Read;
use std::net::{IpAddr, SocketAddr};
use std::path::{Path, PathBuf};

use crate::ai::AiConfig;
use crate::error_reporting::ErrorReportingSettings;
use crate::oidc::ProviderConfig;
use crate::security::TrustedHosts;
use zeroize::Zeroizing;

const DEFAULT_BIND_HOST: &str = "127.0.0.1";
const DEFAULT_BIND_PORT: u16 = 7674;
const DEFAULT_TRUSTED_HOSTS: &str = "localhost,127.0.0.1,[::1]";
const DEFAULT_OIDC_PAT_TTL_SECONDS: u64 = 7 * 24 * 60 * 60;
const MAX_OIDC_SECRET_BYTES: usize = 4096;
const MAX_OIDC_NATIVE_REDIRECTS: usize = 16;
const MAX_OIDC_NATIVE_REDIRECTS_BYTES: usize = 16 * 2048;

pub struct Config {
    pub database_url: String,
    pub media_root: PathBuf,
    pub bind_address: SocketAddr,
    pub secure_cookies: bool,
    pub external_imports: bool,
    pub ocr_enabled: bool,
    pub signup_enabled: bool,
    pub gravatar_enabled: bool,
    pub archive_enabled: bool,
    /// Optional operator-configured error reporting (Sentry); inactive without a DSN.
    pub error_reporting: ErrorReportingSettings,
    /// Validated provider configuration, absent unless `AI_ENABLED=true`.
    pub ai: Option<AiConfig>,
    /// Validated OIDC provider configuration, absent unless explicitly enabled.
    pub oidc: Option<ProviderConfig>,
    /// Maximum lifetime for PATs minted from OIDC login.
    pub oidc_pat_ttl_seconds: u64,
    pub trusted_hosts: TrustedHosts,
    /// The Android app allowed to open this server's links (App Links), when configured.
    pub android_app_links: Option<crate::app_links::AppLinks>,
}

impl fmt::Debug for Config {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("Config")
            .field("database_url", &"[REDACTED]")
            .field("media_root", &self.media_root)
            .field("bind_address", &self.bind_address)
            .field("trusted_hosts", &"[CONFIGURED]")
            .finish()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ConfigError(&'static str);

impl fmt::Display for ConfigError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.0)
    }
}

impl Error for ConfigError {}

impl Config {
    pub fn from_env() -> Result<Self, ConfigError> {
        Self::from_lookup(|key| env::var(key).ok())
    }

    fn from_lookup(mut get: impl FnMut(&str) -> Option<String>) -> Result<Self, ConfigError> {
        let database_url = get("STRICKNANI_DATABASE_URL")
            .filter(|value| !value.trim().is_empty())
            .ok_or(ConfigError("STRICKNANI_DATABASE_URL must be set"))?;
        if !database_url.starts_with("sqlite:") {
            return Err(ConfigError("STRICKNANI_DATABASE_URL must use SQLite"));
        }

        let media_root = get("STRICKNANI_MEDIA_ROOT")
            .filter(|value| !value.trim().is_empty())
            .map(PathBuf::from)
            .ok_or(ConfigError("STRICKNANI_MEDIA_ROOT must be set"))?;
        if !media_root.is_absolute() {
            return Err(ConfigError(
                "STRICKNANI_MEDIA_ROOT must be an absolute path",
            ));
        }

        if let Some(legacy_url) = get("DATABASE_URL") {
            let legacy_url = legacy_url.replace("sqlite+aiosqlite:", "sqlite:");
            if let (Some(rust_path), Some(legacy_path)) =
                (database_path(&database_url), database_path(&legacy_url))
                && rust_path == legacy_path
            {
                return Err(ConfigError("Rust and Python database paths must differ"));
            }
        }
        if let Some(legacy_media) = get("MEDIA_ROOT")
            && resolved_path(&media_root) == resolved_path(std::path::Path::new(&legacy_media))
        {
            return Err(ConfigError("Rust and Python media paths must differ"));
        }

        let bind_host = get("BIND_HOST").unwrap_or_else(|| DEFAULT_BIND_HOST.to_owned());
        let bind_ip = bind_host
            .parse::<IpAddr>()
            .map_err(|_| ConfigError("BIND_HOST must be an IP address"))?;

        let trusted_hosts_raw = get("TRUSTED_HOSTS");
        let trusted_hosts = match trusted_hosts_raw.as_deref() {
            Some(value) => TrustedHosts::parse(value).map_err(|_| {
                ConfigError(
                    "TRUSTED_HOSTS must be a comma-separated list of valid host authorities",
                )
            })?,
            None if bind_ip.is_loopback() => TrustedHosts::loopback_defaults(),
            None => {
                return Err(ConfigError(
                    "TRUSTED_HOSTS must be set when BIND_HOST is not loopback",
                ));
            }
        };

        let port = get("BIND_PORT")
            .or_else(|| get("PORT"))
            .map(|value| {
                value
                    .parse::<u16>()
                    .map_err(|_| ConfigError("BIND_PORT or PORT must be a valid port"))
            })
            .transpose()?
            .unwrap_or(DEFAULT_BIND_PORT);

        let secure_cookies = match get("SESSION_COOKIE_SECURE").as_deref() {
            None | Some("false" | "0") => false,
            Some("true" | "1") => true,
            _ => return Err(ConfigError("SESSION_COOKIE_SECURE must be true or false")),
        };
        let external_imports = match get("EXTERNAL_IMPORTS_ENABLED").as_deref() {
            None | Some("false" | "0") => false,
            Some("true" | "1") => true,
            _ => {
                return Err(ConfigError(
                    "EXTERNAL_IMPORTS_ENABLED must be true or false",
                ));
            }
        };
        let archive_enabled = match get("WAYBACK_ENABLED").as_deref() {
            None | Some("false" | "0") => false,
            Some("true" | "1") => true,
            _ => return Err(ConfigError("WAYBACK_ENABLED must be true or false")),
        };
        let android_app_links = crate::app_links::AppLinks::parse(
            get("ANDROID_APP_PACKAGE").as_deref(),
            get("ANDROID_APP_LINK_FINGERPRINTS").as_deref(),
        )
        .map_err(ConfigError)?;
        let signup_enabled = match get("SIGNUP_ENABLED").as_deref() {
            None | Some("false" | "0") => false,
            Some("true" | "1") => true,
            _ => return Err(ConfigError("SIGNUP_ENABLED must be true or false")),
        };
        let gravatar_enabled = match get("GRAVATAR_ENABLED").as_deref() {
            None | Some("true" | "1") => true,
            Some("false" | "0") => false,
            _ => return Err(ConfigError("GRAVATAR_ENABLED must be true or false")),
        };
        let error_reporting = ErrorReportingSettings::from_lookup(&mut get)
            .map_err(|_| ConfigError("Sentry error reporting configuration is invalid"))?;
        let ocr_enabled = match get("OCR_ENABLED").as_deref() {
            None | Some("false" | "0") => false,
            Some("true" | "1") => true,
            _ => return Err(ConfigError("OCR_ENABLED must be true or false")),
        };
        let ai_enabled = match get("AI_ENABLED").as_deref() {
            None | Some("false" | "0") => false,
            Some("true" | "1") => true,
            _ => return Err(ConfigError("AI_ENABLED must be true or false")),
        };
        let ai = AiConfig::from_lookup(ai_enabled, &mut get)
            .map_err(|_| ConfigError("AI provider configuration is invalid"))?;
        if get("RAVELRY_USERNAME").is_some() != get("RAVELRY_PASSWORD").is_some() {
            return Err(ConfigError(
                "RAVELRY_USERNAME and RAVELRY_PASSWORD must be set together",
            ));
        }
        let oidc_enabled = match get("OIDC_ENABLED").as_deref() {
            None | Some("false" | "0") => false,
            Some("true" | "1") => true,
            _ => return Err(ConfigError("OIDC_ENABLED must be true or false")),
        };
        let (oidc, oidc_pat_ttl_seconds) = if oidc_enabled {
            if !secure_cookies {
                return Err(ConfigError(
                    "SESSION_COOKIE_SECURE must be true when OIDC is enabled",
                ));
            }
            let trusted_hosts_raw = trusted_hosts_raw
                .as_deref()
                .unwrap_or(DEFAULT_TRUSTED_HOSTS);
            let oidc = oidc_from_lookup(&mut get, trusted_hosts_raw)?;
            (Some(oidc.0), oidc.1)
        } else {
            // Disabled OIDC deliberately does not inspect provider environment
            // variables or open any provider files.
            (None, DEFAULT_OIDC_PAT_TTL_SECONDS)
        };
        if !bind_ip.is_loopback() && !secure_cookies {
            return Err(ConfigError(
                "SESSION_COOKIE_SECURE must be true when BIND_HOST is not loopback",
            ));
        }

        Ok(Self {
            database_url,
            media_root,
            bind_address: SocketAddr::new(bind_ip, port),
            secure_cookies,
            external_imports,
            ocr_enabled,
            signup_enabled,
            gravatar_enabled,
            archive_enabled,
            error_reporting,
            ai,
            oidc,
            oidc_pat_ttl_seconds,
            trusted_hosts,
            android_app_links,
        })
    }
}

fn oidc_from_lookup(
    get: &mut impl FnMut(&str) -> Option<String>,
    trusted_hosts: &str,
) -> Result<(ProviderConfig, u64), ConfigError> {
    let issuer = required_oidc_value(get, "OIDC_ISSUER")?;
    let client_id = oidc_client_id_from_lookup(get)?;
    if client_id.is_empty() || client_id.len() > 256 || client_id.chars().any(char::is_control) {
        return Err(ConfigError("OIDC client ID is invalid"));
    }
    let callback_uri = required_oidc_value(get, "OIDC_CALLBACK_URI")?;
    let secret_file = required_oidc_value(get, "OIDC_CLIENT_SECRET_FILE")?;
    let client_secret = read_oidc_client_secret(&secret_file)?;
    let native_redirect_uris = parse_native_redirects(get("OIDC_NATIVE_REDIRECT_URIS"))?;
    let pat_ttl_seconds = get("OIDC_PAT_TTL_SECONDS")
        .map(|value| {
            value
                .parse::<u64>()
                .ok()
                .filter(|seconds| (1..=DEFAULT_OIDC_PAT_TTL_SECONDS).contains(seconds))
                .ok_or(ConfigError("OIDC_PAT_TTL_SECONDS must be from 1 to 604800"))
        })
        .transpose()?
        .unwrap_or(DEFAULT_OIDC_PAT_TTL_SECONDS);
    let provider = ProviderConfig {
        id: "authelia".to_owned(),
        issuer,
        client_id,
        client_secret,
        callback_uri,
        allowed_endpoint_origins: Vec::new(),
        native_redirect_uris,
        allow_http_for_tests: false,
    };
    provider
        .validate()
        .map_err(|_| ConfigError("OIDC provider configuration is invalid"))?;
    if !callback_authority_is_trusted(&provider.callback_uri, trusted_hosts) {
        return Err(ConfigError(
            "OIDC callback authority must exactly match TRUSTED_HOSTS",
        ));
    }
    Ok((provider, pat_ttl_seconds))
}

fn oidc_client_id_from_lookup(
    get: &mut impl FnMut(&str) -> Option<String>,
) -> Result<String, ConfigError> {
    let client_id = get("OIDC_CLIENT_ID");
    let client_id_file = get("OIDC_CLIENT_ID_FILE");
    match (client_id, client_id_file) {
        (Some(value), None) => Ok(value.trim().to_owned()),
        (None, Some(path)) => {
            let value = read_oidc_value_file(&path)?;
            Ok(value.trim().to_owned())
        }
        _ => Err(ConfigError(
            "configure exactly one of OIDC_CLIENT_ID or OIDC_CLIENT_ID_FILE",
        )),
    }
}

fn required_oidc_value(
    get: &mut impl FnMut(&str) -> Option<String>,
    key: &'static str,
) -> Result<String, ConfigError> {
    get(key)
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
        .ok_or(ConfigError("required OIDC configuration is missing"))
}

fn read_oidc_client_secret(path: &str) -> Result<Zeroizing<String>, ConfigError> {
    let value = read_oidc_value_file(path)?;
    let value = Zeroizing::new(value.trim_end_matches(['\r', '\n']).to_owned());
    if value.is_empty() || value.chars().any(char::is_control) {
        return Err(ConfigError("OIDC client secret file is invalid"));
    }
    Ok(value)
}

fn read_oidc_value_file(path: &str) -> Result<Zeroizing<String>, ConfigError> {
    if path.len() > 4096 || !Path::new(path).is_absolute() {
        return Err(ConfigError("OIDC runtime file must use an absolute path"));
    }
    let file = File::open(path).map_err(|_| ConfigError("cannot read OIDC runtime file"))?;
    let mut value = Zeroizing::new(String::new());
    file.take((MAX_OIDC_SECRET_BYTES + 1) as u64)
        .read_to_string(&mut value)
        .map_err(|_| ConfigError("cannot read OIDC runtime file"))?;
    if value.len() > MAX_OIDC_SECRET_BYTES {
        return Err(ConfigError("OIDC runtime file is too large"));
    }
    Ok(value)
}

fn parse_native_redirects(value: Option<String>) -> Result<Vec<String>, ConfigError> {
    let value = value.unwrap_or_else(|| "stricknani://oidc-callback".to_owned());
    if value.len() > MAX_OIDC_NATIVE_REDIRECTS_BYTES {
        return Err(ConfigError("OIDC native redirect allowlist is too large"));
    }
    let redirects: Vec<String> = value.split(',').map(str::trim).map(str::to_owned).collect();
    if redirects.is_empty()
        || redirects.len() > MAX_OIDC_NATIVE_REDIRECTS
        || redirects.iter().any(String::is_empty)
        || redirects.iter().enumerate().any(|(index, redirect)| {
            redirects[..index]
                .iter()
                .any(|previous| previous == redirect)
        })
    {
        return Err(ConfigError("OIDC native redirect allowlist is invalid"));
    }
    Ok(redirects)
}

fn callback_authority_is_trusted(callback_uri: &str, trusted_hosts: &str) -> bool {
    let Ok(callback) = url::Url::parse(callback_uri) else {
        return false;
    };
    let Some(callback_host) = callback.host_str() else {
        return false;
    };
    let Some(callback_port) = callback.port_or_known_default() else {
        return false;
    };
    trusted_hosts.split(',').any(|configured| {
        let configured = configured.trim();
        let Ok(configured) = url::Url::parse(&format!("https://{configured}/")) else {
            return false;
        };
        configured.path() == "/"
            && configured.query().is_none()
            && configured.fragment().is_none()
            && configured.username().is_empty()
            && configured.password().is_none()
            && configured.host_str() == Some(callback_host)
            && configured.port_or_known_default() == Some(callback_port)
    })
}

pub(crate) fn database_path(url: &str) -> Option<PathBuf> {
    use std::str::FromStr;
    let options = sqlx::sqlite::SqliteConnectOptions::from_str(url).ok()?;
    Some(resolved_path(options.get_filename()))
}

fn resolved_path(path: &std::path::Path) -> PathBuf {
    if let Ok(path) = path.canonicalize() {
        return path;
    }
    if let (Some(parent), Some(name)) = (path.parent(), path.file_name())
        && let Ok(parent) = parent.canonicalize()
    {
        return parent.join(name);
    }
    let absolute = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir().unwrap_or_default().join(path)
    };
    let mut result = PathBuf::new();
    for component in absolute.components() {
        match component {
            std::path::Component::ParentDir => {
                result.pop();
            }
            std::path::Component::CurDir => {}
            part => result.push(part.as_os_str()),
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::fs;
    use std::net::SocketAddr;

    use super::{Config, DEFAULT_OIDC_PAT_TTL_SECONDS};

    fn oidc_secret(contents: &[u8]) -> (tempfile::NamedTempFile, String) {
        let file = tempfile::NamedTempFile::new().expect("secret fixture file");
        fs::write(file.path(), contents).expect("write synthetic secret fixture");
        let path = file.path().display().to_string();
        (file, path)
    }

    fn oidc_values(secret_path: &str) -> Vec<(&'static str, String)> {
        vec![
            ("STRICKNANI_DATABASE_URL", "sqlite:/tmp/rust.db".to_owned()),
            ("STRICKNANI_MEDIA_ROOT", "/tmp/rust-media".to_owned()),
            ("SESSION_COOKIE_SECURE", "true".to_owned()),
            ("TRUSTED_HOSTS", "app.example.test".to_owned()),
            ("OIDC_ENABLED", "true".to_owned()),
            ("OIDC_ISSUER", "https://id.example.test".to_owned()),
            ("OIDC_CLIENT_ID", "synthetic-client".to_owned()),
            (
                "OIDC_CALLBACK_URI",
                "https://app.example.test/api/v2/auth/oidc/callback/authelia".to_owned(),
            ),
            ("OIDC_CLIENT_SECRET_FILE", secret_path.to_owned()),
        ]
    }

    fn parse(values: &[(&str, &str)]) -> Result<Config, super::ConfigError> {
        let values: HashMap<_, _> = values
            .iter()
            .map(|(key, value)| ((*key).to_owned(), (*value).to_owned()))
            .collect();
        Config::from_lookup(|key| values.get(key).cloned())
    }

    #[test]
    fn gravatar_is_on_by_default_and_can_be_disabled() {
        let base = [
            ("STRICKNANI_DATABASE_URL", "sqlite:/tmp/rust.db"),
            ("STRICKNANI_MEDIA_ROOT", "/tmp/rust-media"),
        ];
        assert!(parse(&base).expect("default config").gravatar_enabled);
        for (value, expected) in [("true", true), ("1", true), ("false", false), ("0", false)] {
            let mut values = base.to_vec();
            values.push(("GRAVATAR_ENABLED", value));
            assert_eq!(
                parse(&values).expect("valid flag").gravatar_enabled,
                expected
            );
        }
        let mut values = base.to_vec();
        values.push(("GRAVATAR_ENABLED", "maybe"));
        assert!(parse(&values).is_err());
    }

    #[test]
    fn requires_isolated_rust_database_and_media_settings() {
        assert!(parse(&[]).is_err());
        assert!(
            parse(&[
                ("DATABASE_URL", "sqlite:production.db"),
                ("MEDIA_ROOT", "/media")
            ])
            .is_err()
        );
    }

    #[test]
    fn rejects_non_sqlite_database_urls() {
        let error = parse(&[
            ("STRICKNANI_DATABASE_URL", "postgres://db.invalid/test"),
            ("STRICKNANI_MEDIA_ROOT", "/media"),
        ])
        .expect_err("non-SQLite databases must be rejected");
        assert_eq!(error.to_string(), "STRICKNANI_DATABASE_URL must use SQLite");
        assert!(!error.to_string().contains("secret"));
    }

    #[test]
    fn requires_an_absolute_media_root() {
        assert!(
            parse(&[
                ("STRICKNANI_DATABASE_URL", "sqlite:/tmp/test.db"),
                ("STRICKNANI_MEDIA_ROOT", "media")
            ])
            .is_err()
        );
    }

    #[test]
    fn parses_bind_host_and_uses_bind_port_before_port() {
        let config = parse(&[
            ("STRICKNANI_DATABASE_URL", "sqlite:/tmp/test.db"),
            ("STRICKNANI_MEDIA_ROOT", "/tmp/media"),
            ("BIND_HOST", "::1"),
            ("BIND_PORT", "8080"),
            ("PORT", "9090"),
        ])
        .expect("valid configuration");
        assert_eq!(
            config.bind_address,
            SocketAddr::from(([0, 0, 0, 0, 0, 0, 0, 1], 8080))
        );
    }

    #[test]
    fn falls_back_to_port_then_default_and_keeps_static_default() {
        let config = parse(&[
            ("STRICKNANI_DATABASE_URL", "sqlite:/tmp/test.db"),
            ("STRICKNANI_MEDIA_ROOT", "/tmp/media"),
            ("PORT", "9001"),
        ])
        .expect("valid configuration");
        assert_eq!(
            config.bind_address,
            "127.0.0.1:9001".parse().expect("socket address")
        );

        let config = parse(&[
            ("STRICKNANI_DATABASE_URL", "sqlite:/tmp/test.db"),
            ("STRICKNANI_MEDIA_ROOT", "/tmp/media"),
        ])
        .expect("valid configuration");
        assert_eq!(
            config.bind_address,
            "127.0.0.1:7674".parse().expect("socket address")
        );
    }
    #[test]
    fn refuses_explicit_shared_database_or_media_paths() {
        assert!(
            parse(&[
                ("STRICKNANI_DATABASE_URL", "sqlite:///tmp/./shared.db"),
                ("DATABASE_URL", "sqlite:/tmp/shared.db"),
                ("STRICKNANI_MEDIA_ROOT", "/tmp/rust-media"),
            ])
            .is_err()
        );
        assert!(
            parse(&[
                ("STRICKNANI_DATABASE_URL", "sqlite:/tmp/rust.db"),
                ("STRICKNANI_MEDIA_ROOT", "/tmp/shared-media"),
                ("MEDIA_ROOT", "/tmp/./shared-media"),
            ])
            .is_err()
        );
    }

    #[test]
    fn external_listener_requires_explicit_hosts_and_secure_cookies() {
        let base = [
            ("STRICKNANI_DATABASE_URL", "sqlite:/tmp/rust.db"),
            ("STRICKNANI_MEDIA_ROOT", "/tmp/rust-media"),
            ("BIND_HOST", "0.0.0.0"),
        ];
        assert!(parse(&base).is_err());
        let mut values = base.to_vec();
        values.push(("TRUSTED_HOSTS", "rust.example.test,[2001:db8::2]:8443"));
        assert!(parse(&values).is_err());
        values.push(("SESSION_COOKIE_SECURE", "true"));
        let config = parse(&values).expect("external listener has an explicit secure host policy");
        assert_eq!(config.bind_address, "0.0.0.0:7674".parse().unwrap());
    }

    #[test]
    fn loopback_configuration_uses_safe_exact_host_defaults() {
        let config = parse(&[
            ("STRICKNANI_DATABASE_URL", "sqlite:/tmp/rust.db"),
            ("STRICKNANI_MEDIA_ROOT", "/tmp/rust-media"),
        ])
        .expect("loopback defaults are safe");
        assert!(config.trusted_hosts.allows_host("localhost:7674"));
        assert!(!config.trusted_hosts.allows_host("localhost.attacker.test"));
    }

    #[test]
    fn ai_is_disabled_by_default_without_reading_provider_credentials() {
        let mut reads = Vec::new();
        let values = [
            ("STRICKNANI_DATABASE_URL", "sqlite:/tmp/rust.db"),
            ("STRICKNANI_MEDIA_ROOT", "/tmp/rust-media"),
            ("OPENAI_API_KEY", "fixture-secret"),
        ];
        let config = Config::from_lookup(|key| {
            reads.push(key.to_owned());
            values
                .iter()
                .find(|(name, _)| *name == key)
                .map(|(_, value)| (*value).to_owned())
        })
        .expect("AI-disabled config is valid");

        assert!(config.ai.is_none());
        assert!(!reads.iter().any(|key| key.contains("API_KEY")));
    }

    #[test]
    fn ai_requires_a_key_only_when_enabled_and_does_not_debug_secrets() {
        let required = parse(&[
            ("STRICKNANI_DATABASE_URL", "sqlite:/tmp/rust.db"),
            ("STRICKNANI_MEDIA_ROOT", "/tmp/rust-media"),
            ("AI_ENABLED", "true"),
        ])
        .expect_err("enabled AI requires an API key");
        assert_eq!(required.to_string(), "AI provider configuration is invalid");

        let config = parse(&[
            ("STRICKNANI_DATABASE_URL", "sqlite:/tmp/rust.db"),
            ("STRICKNANI_MEDIA_ROOT", "/tmp/rust-media"),
            ("AI_ENABLED", "true"),
            ("AI_API_KEY", "fixture-secret"),
        ])
        .expect("enabled AI config accepts a key");
        let debug = format!("{config:?}");
        assert!(!debug.contains("fixture-secret"));
    }

    #[test]
    fn rejects_invalid_ai_feature_flag() {
        let error = parse(&[
            ("STRICKNANI_DATABASE_URL", "sqlite:/tmp/rust.db"),
            ("STRICKNANI_MEDIA_ROOT", "/tmp/rust-media"),
            ("AI_ENABLED", "yes"),
        ])
        .expect_err("invalid feature flag must fail closed");
        assert_eq!(error.to_string(), "AI_ENABLED must be true or false");
    }

    #[test]
    fn oidc_is_opt_in_and_disabled_mode_does_not_read_provider_environment() {
        let mut reads = Vec::new();
        let config = Config::from_lookup(|key| {
            if key == "OIDC_ENABLED" {
                None
            } else if key.starts_with("OIDC_") {
                reads.push(key.to_owned());
                Some("invalid and deliberately ignored".to_owned())
            } else {
                match key {
                    "STRICKNANI_DATABASE_URL" => Some("sqlite:/tmp/rust.db".to_owned()),
                    "STRICKNANI_MEDIA_ROOT" => Some("/tmp/rust-media".to_owned()),
                    _ => None,
                }
            }
        })
        .expect("OIDC-disabled configuration remains valid");

        assert!(config.oidc.is_none());
        assert_eq!(config.oidc_pat_ttl_seconds, DEFAULT_OIDC_PAT_TTL_SECONDS);
        assert!(reads.is_empty());
    }

    #[test]
    fn oidc_accepts_valid_static_config_and_keeps_secrets_out_of_debug() {
        let (_file, path) = oidc_secret(b"synthetic-client-secret\n");
        let values = oidc_values(&path);
        let config = Config::from_lookup(|key| {
            values
                .iter()
                .find(|(name, _)| *name == key)
                .map(|(_, value)| value.clone())
        })
        .expect("valid OIDC configuration");

        assert_eq!(
            config.oidc.as_ref().map(|provider| provider.id.as_str()),
            Some("authelia")
        );
        assert_eq!(config.oidc_pat_ttl_seconds, DEFAULT_OIDC_PAT_TTL_SECONDS);
        assert!(!format!("{config:?}").contains("synthetic-client-secret"));
    }

    #[test]
    fn oidc_accepts_client_id_file_and_requires_exactly_one_source() {
        let (_secret_file, secret_path) = oidc_secret(b"synthetic-client-secret");
        let (_client_id_file, client_id_path) = oidc_secret(b"synthetic-client\n");
        let mut values = oidc_values(&secret_path);
        values.retain(|(name, _)| *name != "OIDC_CLIENT_ID");
        values.push(("OIDC_CLIENT_ID_FILE", client_id_path));

        let config = Config::from_lookup(|key| {
            values
                .iter()
                .find(|(name, _)| *name == key)
                .map(|(_, value)| value.clone())
        })
        .expect("client ID can be read from its runtime file");
        assert_eq!(config.oidc.as_ref().unwrap().client_id, "synthetic-client");

        values.push(("OIDC_CLIENT_ID", "synthetic-client".to_owned()));
        let error = Config::from_lookup(|key| {
            values
                .iter()
                .find(|(name, _)| *name == key)
                .map(|(_, value)| value.clone())
        })
        .expect_err("inline and file client IDs cannot be combined");
        assert_eq!(
            error.to_string(),
            "configure exactly one of OIDC_CLIENT_ID or OIDC_CLIENT_ID_FILE"
        );

        values.retain(|(name, _)| *name != "OIDC_CLIENT_ID_FILE");
        if let Some((_, value)) = values
            .iter_mut()
            .find(|(name, _)| *name == "OIDC_CLIENT_ID")
        {
            *value = "synthetic\nclient".to_owned();
        }
        let error = Config::from_lookup(|key| {
            values
                .iter()
                .find(|(name, _)| *name == key)
                .map(|(_, value)| value.clone())
        })
        .expect_err("control characters are not accepted in client IDs");
        assert_eq!(error.to_string(), "OIDC client ID is invalid");
    }

    #[test]
    fn oidc_requires_secure_cookies_and_exact_callback_authority() {
        let (_file, path) = oidc_secret(b"fixture-secret");
        let mut values = oidc_values(&path);
        values.retain(|(name, _)| *name != "SESSION_COOKIE_SECURE");
        assert!(
            Config::from_lookup(|key| {
                values
                    .iter()
                    .find(|(name, _)| *name == key)
                    .map(|(_, value)| value.clone())
            })
            .is_err()
        );

        let mut values = oidc_values(&path);
        if let Some((_, callback)) = values
            .iter_mut()
            .find(|(name, _)| *name == "OIDC_CALLBACK_URI")
        {
            *callback =
                "https://app.example.test:8443/api/v2/auth/oidc/callback/authelia".to_owned();
        }
        let error = Config::from_lookup(|key| {
            values
                .iter()
                .find(|(name, _)| *name == key)
                .map(|(_, value)| value.clone())
        })
        .expect_err("callback port must match trusted authority exactly");
        assert_eq!(
            error.to_string(),
            "OIDC callback authority must exactly match TRUSTED_HOSTS"
        );
    }

    #[test]
    fn oidc_rejects_http_callback_and_invalid_pat_lifetimes() {
        let (_file, path) = oidc_secret(b"fixture-secret");
        for (key, value) in [
            (
                "OIDC_CALLBACK_URI",
                "http://app.example.test/api/v2/auth/oidc/callback/authelia",
            ),
            ("OIDC_ISSUER", "http://id.example.test"),
            (
                "OIDC_CALLBACK_URI",
                "https://app.example.test/redirect?next=https://evil.example.test",
            ),
            ("OIDC_PAT_TTL_SECONDS", "0"),
            ("OIDC_PAT_TTL_SECONDS", "604801"),
        ] {
            let mut values = oidc_values(&path);
            values.retain(|(entry, _)| *entry != key);
            values.push((key, value.to_owned()));
            assert!(
                Config::from_lookup(|name| {
                    values
                        .iter()
                        .find(|(entry, _)| *entry == name)
                        .map(|(_, value)| value.clone())
                })
                .is_err(),
                "expected {key}={value} to be rejected"
            );
        }
    }

    #[test]
    fn oidc_secret_file_is_bounded_and_requires_nonempty_secret() {
        let (_file, path) = oidc_secret(&vec![b'x'; super::MAX_OIDC_SECRET_BYTES]);
        assert_eq!(super::read_oidc_client_secret(&path).unwrap().len(), 4096);
        let (_file, path) = oidc_secret(&vec![b'x'; super::MAX_OIDC_SECRET_BYTES + 1]);
        assert!(super::read_oidc_client_secret(&path).is_err());
        let (_file, path) = oidc_secret(b"\n\r");
        assert!(super::read_oidc_client_secret(&path).is_err());
        assert!(super::read_oidc_client_secret("relative-path").is_err());
    }

    #[test]
    fn oidc_native_redirects_reject_untrusted_schemes_and_duplicates() {
        let (_file, path) = oidc_secret(b"fixture-secret");
        for redirects in [
            "http://app.example.test/native-callback",
            "stricknani://oidc-callback,stricknani://oidc-callback",
        ] {
            let mut values = oidc_values(&path);
            values.push(("OIDC_NATIVE_REDIRECT_URIS", redirects.to_owned()));
            assert!(
                Config::from_lookup(|name| {
                    values
                        .iter()
                        .find(|(entry, _)| *entry == name)
                        .map(|(_, value)| value.clone())
                })
                .is_err(),
                "expected native redirects {redirects:?} to be rejected"
            );
        }
    }
}
