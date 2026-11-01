use std::env;
use std::error::Error;
use std::fmt;
use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;

use crate::ai::AiConfig;
use crate::security::TrustedHosts;

const DEFAULT_BIND_HOST: &str = "127.0.0.1";
const DEFAULT_BIND_PORT: u16 = 7674;

pub struct Config {
    pub database_url: String,
    pub media_root: PathBuf,
    pub bind_address: SocketAddr,
    pub static_root: PathBuf,
    pub secure_cookies: bool,
    pub external_imports: bool,
    pub ocr_enabled: bool,
    pub signup_enabled: bool,
    pub archive_enabled: bool,
    /// Validated provider configuration, absent unless `RUST_AI_ENABLED=true`.
    pub ai: Option<AiConfig>,
    pub trusted_hosts: TrustedHosts,
}

impl fmt::Debug for Config {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("Config")
            .field("database_url", &"[REDACTED]")
            .field("media_root", &self.media_root)
            .field("bind_address", &self.bind_address)
            .field("static_root", &self.static_root)
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
        let database_url = get("RUST_DATABASE_URL")
            .filter(|value| !value.trim().is_empty())
            .ok_or(ConfigError("RUST_DATABASE_URL must be set"))?;
        if !database_url.starts_with("sqlite:") {
            return Err(ConfigError("RUST_DATABASE_URL must use SQLite"));
        }

        let media_root = get("RUST_MEDIA_ROOT")
            .filter(|value| !value.trim().is_empty())
            .map(PathBuf::from)
            .ok_or(ConfigError("RUST_MEDIA_ROOT must be set"))?;
        if !media_root.is_absolute() {
            return Err(ConfigError("RUST_MEDIA_ROOT must be an absolute path"));
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

        let trusted_hosts = match get("TRUSTED_HOSTS") {
            Some(value) => TrustedHosts::parse(&value).map_err(|_| {
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
        let external_imports = match get("RUST_EXTERNAL_IMPORTS_ENABLED").as_deref() {
            None | Some("false" | "0") => false,
            Some("true" | "1") => true,
            _ => {
                return Err(ConfigError(
                    "RUST_EXTERNAL_IMPORTS_ENABLED must be true or false",
                ));
            }
        };
        let archive_enabled = match get("RUST_WAYBACK_ENABLED").as_deref() {
            None | Some("false" | "0") => false,
            Some("true" | "1") => true,
            _ => return Err(ConfigError("RUST_WAYBACK_ENABLED must be true or false")),
        };
        let signup_enabled = match get("RUST_SIGNUP_ENABLED").as_deref() {
            None | Some("false" | "0") => false,
            Some("true" | "1") => true,
            _ => return Err(ConfigError("RUST_SIGNUP_ENABLED must be true or false")),
        };
        let ocr_enabled = match get("RUST_OCR_ENABLED").as_deref() {
            None | Some("false" | "0") => false,
            Some("true" | "1") => true,
            _ => return Err(ConfigError("RUST_OCR_ENABLED must be true or false")),
        };
        let ai_enabled = match get("RUST_AI_ENABLED").as_deref() {
            None | Some("false" | "0") => false,
            Some("true" | "1") => true,
            _ => return Err(ConfigError("RUST_AI_ENABLED must be true or false")),
        };
        let ai = AiConfig::from_lookup(ai_enabled, &mut get)
            .map_err(|_| ConfigError("AI provider configuration is invalid"))?;
        if !bind_ip.is_loopback() && !secure_cookies {
            return Err(ConfigError(
                "SESSION_COOKIE_SECURE must be true when BIND_HOST is not loopback",
            ));
        }
        let static_root = get("STATIC_ROOT")
            .map(PathBuf::from)
            .unwrap_or_else(default_static_root);

        Ok(Self {
            database_url,
            media_root,
            bind_address: SocketAddr::new(bind_ip, port),
            static_root,
            secure_cookies,
            external_imports,
            ocr_enabled,
            signup_enabled,
            archive_enabled,
            ai,
            trusted_hosts,
        })
    }
}

fn database_path(url: &str) -> Option<PathBuf> {
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

fn default_static_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../stricknani/static")
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::net::SocketAddr;
    use std::path::PathBuf;

    use super::Config;

    fn parse(values: &[(&str, &str)]) -> Result<Config, super::ConfigError> {
        let values: HashMap<_, _> = values
            .iter()
            .map(|(key, value)| ((*key).to_owned(), (*value).to_owned()))
            .collect();
        Config::from_lookup(|key| values.get(key).cloned())
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
            ("RUST_DATABASE_URL", "postgres://db.invalid/test"),
            ("RUST_MEDIA_ROOT", "/media"),
        ])
        .expect_err("non-SQLite databases must be rejected");
        assert_eq!(error.to_string(), "RUST_DATABASE_URL must use SQLite");
        assert!(!error.to_string().contains("secret"));
    }

    #[test]
    fn requires_an_absolute_media_root() {
        assert!(
            parse(&[
                ("RUST_DATABASE_URL", "sqlite:/tmp/test.db"),
                ("RUST_MEDIA_ROOT", "media")
            ])
            .is_err()
        );
    }

    #[test]
    fn parses_bind_host_and_uses_bind_port_before_port() {
        let config = parse(&[
            ("RUST_DATABASE_URL", "sqlite:/tmp/test.db"),
            ("RUST_MEDIA_ROOT", "/tmp/media"),
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
            ("RUST_DATABASE_URL", "sqlite:/tmp/test.db"),
            ("RUST_MEDIA_ROOT", "/tmp/media"),
            ("PORT", "9001"),
        ])
        .expect("valid configuration");
        assert_eq!(
            config.bind_address,
            "127.0.0.1:9001".parse().expect("socket address")
        );
        assert_eq!(
            config.static_root,
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../stricknani/static")
        );

        let config = parse(&[
            ("RUST_DATABASE_URL", "sqlite:/tmp/test.db"),
            ("RUST_MEDIA_ROOT", "/tmp/media"),
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
                ("RUST_DATABASE_URL", "sqlite:///tmp/./shared.db"),
                ("DATABASE_URL", "sqlite:/tmp/shared.db"),
                ("RUST_MEDIA_ROOT", "/tmp/rust-media"),
            ])
            .is_err()
        );
        assert!(
            parse(&[
                ("RUST_DATABASE_URL", "sqlite:/tmp/rust.db"),
                ("RUST_MEDIA_ROOT", "/tmp/shared-media"),
                ("MEDIA_ROOT", "/tmp/./shared-media"),
            ])
            .is_err()
        );
    }

    #[test]
    fn external_listener_requires_explicit_hosts_and_secure_cookies() {
        let base = [
            ("RUST_DATABASE_URL", "sqlite:/tmp/rust.db"),
            ("RUST_MEDIA_ROOT", "/tmp/rust-media"),
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
            ("RUST_DATABASE_URL", "sqlite:/tmp/rust.db"),
            ("RUST_MEDIA_ROOT", "/tmp/rust-media"),
        ])
        .expect("loopback defaults are safe");
        assert!(config.trusted_hosts.allows_host("localhost:7674"));
        assert!(!config.trusted_hosts.allows_host("localhost.attacker.test"));
    }

    #[test]
    fn ai_is_disabled_by_default_without_reading_provider_credentials() {
        let mut reads = Vec::new();
        let values = [
            ("RUST_DATABASE_URL", "sqlite:/tmp/rust.db"),
            ("RUST_MEDIA_ROOT", "/tmp/rust-media"),
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
            ("RUST_DATABASE_URL", "sqlite:/tmp/rust.db"),
            ("RUST_MEDIA_ROOT", "/tmp/rust-media"),
            ("RUST_AI_ENABLED", "true"),
        ])
        .expect_err("enabled AI requires an API key");
        assert_eq!(required.to_string(), "AI provider configuration is invalid");

        let config = parse(&[
            ("RUST_DATABASE_URL", "sqlite:/tmp/rust.db"),
            ("RUST_MEDIA_ROOT", "/tmp/rust-media"),
            ("RUST_AI_ENABLED", "true"),
            ("AI_API_KEY", "fixture-secret"),
        ])
        .expect("enabled AI config accepts a key");
        let debug = format!("{config:?}");
        assert!(!debug.contains("fixture-secret"));
    }

    #[test]
    fn rejects_invalid_ai_feature_flag() {
        let error = parse(&[
            ("RUST_DATABASE_URL", "sqlite:/tmp/rust.db"),
            ("RUST_MEDIA_ROOT", "/tmp/rust-media"),
            ("RUST_AI_ENABLED", "yes"),
        ])
        .expect_err("invalid feature flag must fail closed");
        assert_eq!(error.to_string(), "RUST_AI_ENABLED must be true or false");
    }
}
