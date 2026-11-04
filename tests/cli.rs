use std::{
    io::{Read, Write},
    net::TcpListener,
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant},
};
use stricknani_server::db;

fn cli(url: &str, args: &[&str], input: Option<&[u8]>) -> std::process::Output {
    let mut child = Command::new(env!("CARGO_BIN_EXE_stricknani-server"))
        .args(args)
        .env("RUST_LOG", "error")
        .env("RUST_DATABASE_URL", url)
        .env(
            "RUST_MEDIA_ROOT",
            std::env::temp_dir().join("stricknani-cli-test-media"),
        )
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    if let Some(input) = input {
        child.stdin.take().unwrap().write_all(input).unwrap();
    } else {
        drop(child.stdin.take());
    }
    child.wait_with_output().unwrap()
}

fn health_probe(port: u16) -> std::process::Output {
    let mut command = Command::new(env!("CARGO_BIN_EXE_stricknani-server"));
    command
        .arg("health-check")
        .env_clear()
        .env("BIND_PORT", port.to_string())
        .env("TRUSTED_HOSTS", "localhost");
    for name in ["LD_LIBRARY_PATH", "NIX_LD", "NIX_LD_LIBRARY_PATH"] {
        if let Some(value) = std::env::var_os(name) {
            command.env(name, value);
        }
    }
    command.output().unwrap()
}

fn health_server(status: &'static str) -> (u16, thread::JoinHandle<Option<String>>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let port = listener.local_addr().unwrap().port();
    let worker = thread::spawn(move || {
        let deadline = Instant::now() + Duration::from_secs(2);
        let (mut stream, _) = loop {
            match listener.accept() {
                Ok(connection) => break connection,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    if Instant::now() >= deadline {
                        return None;
                    }
                    thread::sleep(Duration::from_millis(10));
                }
                Err(_) => return None,
            }
        };
        stream
            .set_read_timeout(Some(Duration::from_secs(1)))
            .unwrap();
        stream
            .set_write_timeout(Some(Duration::from_secs(1)))
            .unwrap();
        let mut request = [0; 2048];
        let count = stream.read(&mut request).ok()?;
        let request = String::from_utf8_lossy(&request[..count]).into_owned();
        if write!(
            stream,
            "HTTP/1.1 {status}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        )
        .is_err()
        {
            return None;
        }
        Some(request)
    });
    (port, worker)
}

#[test]
fn health_check_accepts_only_local_success_response() {
    let (port, server) = health_server("200 OK");
    let result = health_probe(port);
    assert!(result.status.success());
    assert_eq!(String::from_utf8_lossy(&result.stdout).trim(), "healthy");
    assert!(result.stderr.len() <= 512, "health-check stderr is bounded");
    let request = server.join().unwrap().expect("health probe should connect");
    assert!(request.starts_with("GET /healthz HTTP/1.1\r\n"));
    assert!(request.lines().any(|line| {
        line.split_once(':').is_some_and(|(name, value)| {
            name.eq_ignore_ascii_case("host") && value.trim() == "localhost"
        })
    }));

    let (port, server) = health_server("503 Service Unavailable");
    let result = health_probe(port);
    assert!(!result.status.success());
    assert!(result.stderr.len() <= 512, "health-check stderr is bounded");
    assert_eq!(
        String::from_utf8_lossy(&result.stderr).trim(),
        "stricknani-server: health endpoint returned a non-success status"
    );
    server.join().unwrap().expect("health probe should connect");
}

#[test]
fn health_check_timeout_is_bounded_and_has_fixed_error() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let port = listener.local_addr().unwrap().port();
    let _server = thread::spawn(move || {
        let deadline = Instant::now() + Duration::from_secs(2);
        loop {
            match listener.accept() {
                Ok((_stream, _)) => {
                    thread::sleep(Duration::from_secs(4));
                    return;
                }
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    if Instant::now() >= deadline {
                        return;
                    }
                    thread::sleep(Duration::from_millis(10));
                }
                Err(_) => return,
            }
        }
    });
    let started = Instant::now();

    let result = health_probe(port);
    let elapsed = started.elapsed();

    assert!(!result.status.success());
    assert!(elapsed < Duration::from_secs(6));
    assert!(result.stderr.len() <= 512, "health-check stderr is bounded");
    assert_eq!(
        String::from_utf8_lossy(&result.stderr).trim(),
        "stricknani-server: health check timed out"
    );
}

#[tokio::test]
async fn account_cli_creates_disables_and_revokes_without_exposing_passwords() {
    let path = std::env::temp_dir().join(format!("stricknani-cli-{}.db", rand::random::<u64>()));
    let url = format!("sqlite:{}", path.display());
    let created = cli(
        &url,
        &["create-user", "cli@example.invalid"],
        Some(b"disposable-test-password\n"),
    );
    assert!(
        created.status.success(),
        "{}",
        String::from_utf8_lossy(&created.stderr)
    );
    assert_eq!(
        String::from_utf8_lossy(&created.stdout).trim(),
        "user created"
    );
    assert!(created.stderr.is_empty());
    let pool = db::connect(&url).await.unwrap();
    let user_id: i64 = sqlx::query_scalar("SELECT id FROM users")
        .fetch_one(&pool)
        .await
        .unwrap();
    let is_admin: i64 = sqlx::query_scalar("SELECT is_admin FROM users WHERE id = ?")
        .bind(user_id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(is_admin, 0);
    sqlx::query("INSERT INTO api_tokens (user_id,name,token_hash,created_at) VALUES (?,'test','test-hash',unixepoch())")
        .bind(user_id).execute(&pool).await.unwrap();
    let token_id: i64 = sqlx::query_scalar("SELECT id FROM api_tokens")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert!(
        cli(&url, &["revoke-token", &token_id.to_string()], None)
            .status
            .success()
    );
    let revoked: i64 = sqlx::query_scalar("SELECT revoked_at IS NOT NULL FROM api_tokens")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(revoked, 1);
    assert!(
        cli(&url, &["disable-user", "CLI@example.invalid"], None)
            .status
            .success()
    );
    let active: i64 = sqlx::query_scalar("SELECT active FROM users")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(active, 0);
    assert!(!cli(&url, &["revoke-token", "-1"], None).status.success());
    pool.close().await;
    std::fs::remove_file(path).unwrap();
}

#[tokio::test]
async fn create_admin_reads_password_from_stdin_and_stores_admin_role_atomically() {
    let path =
        std::env::temp_dir().join(format!("stricknani-admin-cli-{}.db", rand::random::<u64>()));
    let url = format!("sqlite:{}", path.display());
    let password = b"disposable-admin-password-for-test\n";
    let created = cli(
        &url,
        &["create-admin", "bootstrap@example.invalid"],
        Some(password),
    );
    assert!(created.status.success());
    assert_eq!(
        String::from_utf8_lossy(&created.stdout).trim(),
        "admin created"
    );
    assert!(
        !String::from_utf8_lossy(&created.stdout)
            .contains(std::str::from_utf8(password).unwrap().trim())
    );
    assert!(
        !String::from_utf8_lossy(&created.stderr)
            .contains(std::str::from_utf8(password).unwrap().trim())
    );

    let pool = db::connect(&url).await.unwrap();
    let account = sqlx::query_as::<_, (String, i64)>("SELECT email, is_admin FROM users")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(account, ("bootstrap@example.invalid".to_owned(), 1));

    let invalid_password = b"short\n";
    let rejected = cli(
        &url,
        &["create-admin", "rejected@example.invalid"],
        Some(invalid_password),
    );
    assert!(!rejected.status.success());
    assert!(
        !String::from_utf8_lossy(&rejected.stdout)
            .contains(std::str::from_utf8(invalid_password).unwrap().trim())
    );
    assert!(
        !String::from_utf8_lossy(&rejected.stderr)
            .contains(std::str::from_utf8(invalid_password).unwrap().trim())
    );
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM users")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(count, 1);
    pool.close().await;
    std::fs::remove_file(path).unwrap();
}

#[test]
fn help_needs_no_database_and_invalid_config_is_redacted() {
    let help = Command::new(env!("CARGO_BIN_EXE_stricknani-server"))
        .arg("--help")
        .env_remove("RUST_DATABASE_URL")
        .env_remove("RUST_MEDIA_ROOT")
        .output()
        .unwrap();
    assert!(help.status.success());
    assert!(String::from_utf8_lossy(&help.stdout).contains("create-admin EMAIL"));
    assert!(String::from_utf8_lossy(&help.stdout).contains("health-check"));
    let failed = cli("invalid-sensitive-test-value", &["serve"], None);
    assert!(!failed.status.success());
    assert!(!String::from_utf8_lossy(&failed.stderr).contains("invalid-sensitive-test-value"));
}
